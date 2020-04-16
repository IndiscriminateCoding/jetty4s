package jetty4s.common

import java.security.KeyStore

trait SSLKeyStore

object SSLKeyStore {
  case class FileKeyStore(path: String, password: String) extends SSLKeyStore
  case class JavaKeyStore(keyStore: KeyStore, password: String) extends SSLKeyStore
}
