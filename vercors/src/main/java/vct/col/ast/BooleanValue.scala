package vct.col.ast

class BooleanValue(val value:Boolean) extends Value {
  def getValue() = value
  override def toString() = if (value) "true" else "false"
  override def equals(o:Any) = o.equals(value)
}