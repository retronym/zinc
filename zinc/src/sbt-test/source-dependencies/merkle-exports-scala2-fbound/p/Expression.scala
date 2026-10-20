package e

abstract class Expression extends t.TreeNode[Expression]

abstract class UnaryExpression extends Expression {
  def child: Expression
  def children: Seq[Expression] = child :: Nil
}
