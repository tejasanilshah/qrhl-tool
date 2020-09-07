package isabelle

import isabelle.control.{Isabelle, MLFunction, MLFunction2, MLValue}
import isabelle.control.MLValue.Converter
import Thm.Ops

import scala.concurrent.{ExecutionContext, Future}

// Implicits
import MLValue.Implicits._
import Thm.Implicits._
import Cterm.Implicits._
import Context.Implicits._

final class Thm private [Thm](val mlValue : MLValue[Thm])(implicit ec: ExecutionContext, isabelle: Isabelle) {
  override def toString: String = s"thm${mlValue.stateString}"
  lazy val cterm : Cterm = Cterm(Ops.cpropOf(mlValue))
  def pretty(ctxt: Context)(implicit ec: ExecutionContext): String =
    Ops.stringOfThm(MLValue(ctxt, this)).retrieveNow
}

object Thm {
  private[isabelle] class Ops(implicit val isabelle: Isabelle, ec: ExecutionContext) {
    import MLValue.compileFunction
    Term.init(isabelle)
    isabelle.executeMLCodeNow("exception E_Thm of thm")
    val getThm: MLFunction2[Context, String, Thm] =
      compileFunction("fn (ctxt, name) => Proof_Context.get_thm ctxt name")
    val cpropOf: MLFunction[Thm, Cterm] =
      compileFunction[Thm, Cterm]("Thm.cprop_of")
    val stringOfThm: MLFunction2[Context, Thm, String] =
      compileFunction("fn (ctxt, thm) => Thm.pretty_thm ctxt thm |> Pretty.unformatted_string_of |> YXML.content_of")
  }

  var Ops : Ops = _

  // TODO Ugly hack, fails if there are several Isabelle objects
  def init(isabelle: Isabelle)(implicit ec: ExecutionContext): Unit = synchronized {
    if (Ops == null)
      Ops = new Ops()(isabelle, ec)
  }

  def apply(context: Context, name: String)(implicit isabelle: Isabelle, ec: ExecutionContext): Thm = {
    val mlThm : MLValue[Thm] = Ops.getThm(MLValue((context, name)))
    new Thm(mlThm)
  }

  object ThmConverter extends Converter[Thm] {
    override def retrieve(value: MLValue[Thm])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Thm] =
      for (_ <- value.id)
        yield new Thm(mlValue = value)
    override def store(value: Thm)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[Thm] =
      value.mlValue
    override val exnToValue: String = "fn E_Thm thm => thm"
    override val valueToExn: String = "E_Thm"
  }

  object Implicits {
    implicit val thmConverter: ThmConverter.type = ThmConverter
  }
}

