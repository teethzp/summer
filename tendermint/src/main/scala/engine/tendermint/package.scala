package engine

import util.crypto.PublicKey
import util.collections.FixedSizeArray
import util.crypto.Hashable

package object tendermint {
  type Address = FixedSizeArray[Byte]
  type NodeID = Int

  def getAddress(publicKey: PublicKey): Address = {
    val hash = Hashable.hash(publicKey)
    require(hash.length == 32, "The length of hash must be 32")
    val slice = hash.slice(12, 31)
    val address = new Address(20)
    address.data = slice
    address
  }
}
