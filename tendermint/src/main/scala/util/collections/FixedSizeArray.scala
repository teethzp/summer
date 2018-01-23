package util
package collections

import scala.collection.mutable
import scala.reflect.ClassTag

class FixedSizeArray[T : ClassTag](private val len: Int) {
  private var bytes = new Array[T](len)

  def data: Array[T] = bytes
  def data_=(rhs: FixedSizeArray[T]): Unit = {
    require(rhs.len == this.len, "FixedSizeArray can't accept different length")
    bytes = rhs.bytes
  }
  def data_=(rhs: Array[T]): Unit = {
    require(rhs.length == this.len, "FixedSizeArray can't accept different length")
    bytes = rhs
  }

  override def toString: String = {
    val builder = new mutable.StringBuilder
    builder += '['
    bytes.foreach(elem => builder ++= s"$elem ")
    builder += ']'
    builder.result
  }
}