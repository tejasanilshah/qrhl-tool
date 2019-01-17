package qrhl.toplevel

import java.nio.file.{Path, Paths}

import org.scalatest.{FlatSpec, FunSuite}
import qrhl.UserException
import qrhl.isabelle.Isabelle

class ToplevelTest extends FunSuite {
  test("assign statement should throw UserException on undefined variable") {
    val toplevel = Toplevel.makeToplevel()
    assertThrows[UserException] {
      toplevel.execCmd("program prog := { x <- (); }")
    }
  }

  test("name collision between program and variable should have proper error message") {
    val toplevel = Toplevel.makeToplevel()
    toplevel.execCmd("classical var x : int")
    val exn = intercept[UserException] {
      toplevel.execCmd("program x := { skip; }") }
    assert(exn.msg.startsWith("Name x already used for a variable or program"))
  }
}

object ToplevelTest {
//  val isabellePath = "auto"
//  lazy val isabelle = new Isabelle(isabellePath)
//  def makeToplevel(): Toplevel = Toplevel.makeToplevel()
}
