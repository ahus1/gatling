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

package io.gatling.http.cache

import java.{ util => ju }
import java.net.InetAddress

import io.gatling.commons.validation._
import io.gatling.core.CoreComponents
import io.gatling.core.session.{ Session, SessionPrivateAttributes }
import io.gatling.http.client.resolver.InetAddressNameResolver
import io.gatling.http.engine.HttpEngine
import io.gatling.http.protocol.{ AsyncDnsNameResolution, HttpProtocolDnsPart, JavaDnsNameResolution }
import io.gatling.http.resolver.{ AliasesAwareNameResolver, SharedAsyncDnsNameResolverFactory, ShufflingNameResolver }

import io.netty.resolver.dns.DefaultDnsCache

private[http] object DnsCacheSupport {
  val DnsNameResolverAttributeName: String = SessionPrivateAttributes.generatePrivateAttribute("http.cache.dns")
}

private[http] trait DnsCacheSupport {

  import DnsCacheSupport._

  def coreComponents: CoreComponents

  private def setDecoratedResolver(
      session: Session,
      resolver: InetAddressNameResolver,
      hostNameAliases: Map[String, ju.List[InetAddress]]
  ): Session = {
    val shufflingResolver = new ShufflingNameResolver(resolver, session.eventLoop)
    val aliasAwareResolver = AliasesAwareNameResolver(hostNameAliases, shufflingResolver)
    session.set(DnsNameResolverAttributeName, aliasAwareResolver)
  }

  def setNameResolver(dnsPart: HttpProtocolDnsPart, httpEngine: HttpEngine): Session => Session = {

    import dnsPart._

    dnsNameResolution match {
      case JavaDnsNameResolution =>
        val actualResolver = httpEngine.newJavaDnsNameResolver
        setDecoratedResolver(_, actualResolver, hostNameAliases)
      case AsyncDnsNameResolution(dnsServers) =>
        if (perUserNameResolution) { session =>
          {
            val actualResolver = httpEngine.newAsyncDnsNameResolver(session.eventLoop, dnsServers, new DefaultDnsCache)
            setDecoratedResolver(session, actualResolver, hostNameAliases)
          }
        } else {
          val factory = SharedAsyncDnsNameResolverFactory(httpEngine, dnsServers, coreComponents.actorSystem)
          session => {
            val sharedResolver = factory(session.eventLoop)
            setDecoratedResolver(session, sharedResolver, hostNameAliases)
          }
        }
    }
  }

  def nameResolver(session: Session): Validation[InetAddressNameResolver] =
    session.attributes.get(DnsNameResolverAttributeName).map(_.asInstanceOf[InetAddressNameResolver]).toValidation("DnsNameResolver missing from Session")
}
