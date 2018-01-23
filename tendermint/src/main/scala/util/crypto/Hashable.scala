package util
package crypto

import scorex.crypto.hash.{Blake2b256, Digest32}

object Hashable {
  def hash(bytes: Array[Byte]): Digest32 = {
    Blake2b256.hash(bytes)
  }
}
