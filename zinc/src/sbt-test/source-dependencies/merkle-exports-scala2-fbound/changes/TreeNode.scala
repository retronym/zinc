package t

abstract class TreeNode[BaseType <: TreeNode[BaseType]] extends Product { self: BaseType =>
  def children: Seq[BaseType]
  def bench1: Int = 1
}
