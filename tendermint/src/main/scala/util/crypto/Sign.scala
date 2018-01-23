package util
package crypto

import scorex.crypto.signatures.Curve25519

object Sign {
  def createKeyPair: (PrivateKey, PublicKey) = {
    Curve25519.createKeyPair
  }

  def sign(message: Array[Byte], privateKey: PrivateKey): Signature = {
    Curve25519.sign(privateKey, message)
  }

  def verify(signature: Signature, message: Array[Byte], publicKey: PublicKey): Boolean = {
    Curve25519.verify(signature, message, publicKey)
  }
}
