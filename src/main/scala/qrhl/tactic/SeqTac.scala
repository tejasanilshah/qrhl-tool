package qrhl.tactic

import qrhl._
import qrhl.logic.{Block, Expression}


case class SeqTac(left:Int, right:Int, inner:Expression) extends Tactic {
  assert(left>=0)
  assert(right>=0)
  override def apply(state: State, goal: Subgoal): List[Subgoal] = goal match {
    case QRHLSubgoal(leftProg,rightProg,pre,post,assms) =>
      if (leftProg.length < left)
        throw UserException(s"Left program contains ${leftProg.length} statements, but we try to split after the ${left}th")
      if (rightProg.length < right)
        throw UserException(s"Right program contains ${rightProg.length} statements, but we try to split after the ${right}th")
      val left1 = Block(leftProg.statements.take(left):_*)
      val left2 = Block(leftProg.statements.drop(left):_*)
      val right1 = Block(rightProg.statements.take(right):_*)
      val right2 = Block(rightProg.statements.drop(right):_*)
      List(
        QRHLSubgoal(left1,right1,pre,inner,assms),
        QRHLSubgoal(left2,right2,inner,post,assms)
      )
    case _ => throw UserException("Expected a qRHL subgoal")
  }
}
