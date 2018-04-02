package qrhl.tactic

import org.scalatest.FlatSpec
import qrhl.UserException
import qrhl.toplevel.{Toplevel, ToplevelTest}

class CallTacTest extends FlatSpec {
  def toplevel(): Toplevel = {
    val tl = ToplevelTest.makeToplevel()
    tl.run(
      """classical var x : bit.
        |quantum var q : bit.
        |program p := { x <- undefined; on q apply undefined; }.
      """.stripMargin)
    tl
  }

  "call tactic" should "permit postcondition to contain the quantum variable equality" in {
    val tl = toplevel()
    tl.execCmd("qrhl {top} call p; ~ call p; {Qeq[q1=q2]}")
    val state2 = tl.state.applyTactic(CallTac)
    state2.goal.foreach(_.checkWelltyped())
    assert(state2.goal.length==2)
  }
}
