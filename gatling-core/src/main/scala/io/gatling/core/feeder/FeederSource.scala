/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.feeder

import java.nio.channels.FileChannel
import java.nio.charset.Charset

import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.json.{ Json, JsonParsers }
import io.gatling.core.util._

import com.typesafe.scalalogging.LazyLogging

private[gatling] sealed trait FeederSource[T] {
  def feeder(options: FeederOptions[T], configuration: GatlingConfiguration): Feeder[Any]
}

private[gatling] final case class InMemoryFeederSource[T](records: IndexedSeq[Record[T]]) extends FeederSource[T] with LazyLogging {

  require(records.nonEmpty, "Feeder must not be empty")

  override def feeder(options: FeederOptions[T], configuration: GatlingConfiguration): Feeder[Any] =
    InMemoryFeeder(records, options.conversion, options.strategy)
}

private[gatling] final class JsonFileFeederSource(resource: Resource, jsonParsers: JsonParsers, charset: Charset) extends FeederSource[Any] {
  override def feeder(options: FeederOptions[Any], configuration: GatlingConfiguration): Feeder[Any] = {

    val uncompressedResource =
      if (options.unzip) {
        Unzip.unzip(resource)
      } else {
        resource
      }

    val records = Using.resource(uncompressedResource.inputStream) { is =>
      val node = jsonParsers.parse(is, charset)
      if (node.isArray) {
        node.elements.asScala.collect {
          case node if node.isObject => Json.asScala(node).asInstanceOf[collection.immutable.Map[String, Any]]
        }.toVector
      } else {
        throw new IllegalArgumentException("Root element of JSON feeder file isn't an array")
      }
    }

    InMemoryFeeder(records, options.conversion, options.strategy)
  }
}

private[gatling] final class SeparatedValuesFeederSource(resource: Resource, separator: Char, quoteChar: Char) extends FeederSource[String] {

  override def feeder(options: FeederOptions[String], configuration: GatlingConfiguration): Feeder[Any] = {

    val uncompressedResource =
      if (options.unzip) {
        Unzip.unzip(resource)
      } else {
        resource
      }

    def applyBatch(res: Resource): Feeder[Any] = {
      val charset = configuration.core.charset
      options.loadingMode match {
        case Batch(bufferSize) =>
          BatchedSeparatedValuesFeeder(res.file, separator, quoteChar, options.conversion, options.strategy, bufferSize, charset)
        case Adaptive if res.file.length > configuration.core.feederAdaptiveLoadModeThreshold =>
          BatchedSeparatedValuesFeeder(res.file, separator, quoteChar, options.conversion, options.strategy, Batch.DefaultBufferLines, charset)
        case _ =>
          val records = Using.resource(FileChannel.open(res.file.toPath)) { channel =>
            SeparatedValuesParser.stream(separator, quoteChar, charset)(channel).toVector
          }

          InMemoryFeeder(records, options.conversion, options.strategy)
      }
    }

    applyBatch(uncompressedResource)
  }
}
