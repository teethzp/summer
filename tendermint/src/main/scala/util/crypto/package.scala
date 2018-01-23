package util

import scorex.crypto.hash.Digest64
import scorex.crypto.signatures

package object crypto {
  type Signature = signatures.Signature
  type PrivateKey = signatures.PrivateKey
  type PublicKey = signatures.PublicKey
  type KeyPair = (PublicKey, PrivateKey)
  type Hash = Digest64
}
