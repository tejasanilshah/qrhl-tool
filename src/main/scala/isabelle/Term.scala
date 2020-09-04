package isabelle

import isabelle.control.{Isabelle, MLFunction, MLValue}

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}
import isabelle.control.MLValue.Converter
import MLValue.Implicits._
import Term.Implicits._
import Cterm.Implicits._
import Typ.Implicits._
import Context.Implicits._

sealed abstract class Term {
  val mlValue : MLValue[Term]
  implicit val isabelle : Isabelle
  def pretty(ctxt: Context)(implicit ec: ExecutionContext): String =
    Term.stringOfTerm(MLValue((ctxt, this))).retrieveNow
  val concrete : Term
  def $(that: Term): Term = App(this, that)
  override def equals(that: Any): Boolean = (this, that) match {
    case (t1, t2: AnyRef) if t1 eq t2 => true
    case (t1: App, t2: App) => t1.fun == t2.fun && t1.arg == t2.arg
    case (t1: Bound, t2: Bound) => t1.index == t2.index
    case (t1: Var, t2: Var) => t1.name == t2.name && t1.index == t2.index && t1.typ == t2.typ
    case (t1: Free, t2: Free) => t1.name == t2.name && t1.typ == t2.typ
    case (t1: Const, t2: Const) => t1.name == t2.name && t1.typ == t2.typ
    case (t1: Abs, t2: Abs) => t1.name == t2.name && t1.typ == t2.typ && t1.body == t2.body
    case (t1: Cterm, t2: Cterm) => t1.mlValueTerm == t2.mlValueTerm
    case (t1: Cterm, t2: Term) => t1.mlValueTerm == t2
    case (t1: Term, t2: Cterm) => t1 == t2.mlValueTerm
    case (t1: MLValueTerm, t2: MLValueTerm) =>
      import ExecutionContext.Implicits.global
      if (t1.concreteComputed && t2.concreteComputed) t1.concrete == t2.concrete
      else Term.equalsTerm(MLValue((t1,t2))).retrieveNow
    case (t1: MLValueTerm, t2: Term) =>
      import ExecutionContext.Implicits.global
      if (t1.concreteComputed) t1.concrete == t2
      else Term.equalsTerm(MLValue((t1,t2))).retrieveNow
    case (t1: Term, t2: MLValueTerm) =>
      import ExecutionContext.Implicits.global
      if (t2.concreteComputed) t1 == t2.concrete
      else Term.equalsTerm(MLValue((t1,t2))).retrieveNow
    case _ => false
  }
}

final class Cterm private(val ctermMlValue: MLValue[Cterm])(implicit val isabelle: Isabelle, ec: ExecutionContext) extends Term {
  override lazy val mlValue: MLValue[Term] = Term.termOfCterm(ctermMlValue)
  def mlValueTerm = new MLValueTerm(mlValue)
  override def pretty(ctxt: Context)(implicit ec: ExecutionContext): String =
    Term.stringOfCterm(MLValue((ctxt, this))).retrieveNow
  lazy val concrete: Term = new MLValueTerm(mlValue).concrete
}

object Cterm {
  def apply(mlValue: MLValue[Cterm])
           (implicit isabelle: Isabelle, executionContext: ExecutionContext) =
    new Cterm(mlValue)

  def apply(ctxt: Context, term: Term)(implicit isabelle: Isabelle, ec: ExecutionContext) : Cterm = {
    implicit val _ = ctxt
    new Cterm(Term.ctermOfTerm(MLValue((ctxt, term))))
  }

  object CtermConverter extends Converter[Cterm] {
    override def store(value: Cterm)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[Cterm] =
      value.ctermMlValue
    override def retrieve(value: MLValue[Cterm])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Cterm] =
      for (_ <- value.id)
        yield new Cterm(ctermMlValue = value)
    override lazy val exnToValue: String = "fn (E_Cterm t) => t"
    override lazy val valueToExn: String = "E_Cterm"
  }

  object Implicits {
    implicit val ctermConverter: CtermConverter.type = CtermConverter
  }

}

final class MLValueTerm(val mlValue: MLValue[Term])(implicit val isabelle: Isabelle, ec: ExecutionContext) extends Term {
  @inline private def await[A](awaitable: Awaitable[A]) : A = Await.result(awaitable, Duration.Inf)

  def concreteComputed: Boolean = concreteLoaded
  @volatile private var concreteLoaded = false
  lazy val concrete : Term = {
    val term = Term.whatTerm(mlValue).retrieveNow match {
      case 1 => // Const
        val name = Term.termName(mlValue).retrieve
        val typ = new MLValueTyp(Term.termTyp(mlValue))
        new Const(await(name), typ, mlValue)
      case 2 => // Free
        val name = Term.termName(mlValue).retrieve
        val typ = new MLValueTyp(Term.termTyp(mlValue))
        new Free(await(name), typ, mlValue)
      case 3 =>
        val name = Term.termName(mlValue).retrieve
        val typ = new MLValueTyp(Term.termTyp(mlValue))
        ??? // Var
      case 4 => ??? // Bound
      case 5 =>
        val name = Term.termName(mlValue).retrieve
        val typ = new MLValueTyp(Term.termTyp(mlValue))
        ??? // Abs
      case 6 => // App
        val (t1,t2) = Term.dest_App(this.mlValue).retrieveNow
        t1 $ t2
    }
    concreteLoaded = true
    term
  }

  override def toString: String =
    if (concreteLoaded) concrete.toString
    else s"‹term${mlValue.stateString}›"
}

final class Const private[isabelle] (val name: String, val typ: Typ, val initialMlValue: MLValue[Term]=null)
                                  (implicit val isabelle: Isabelle, ec: ExecutionContext) extends Term {
  lazy val mlValue : MLValue[Term] =
    if (initialMlValue!=null) initialMlValue
    else Term.makeConst(MLValue((name,typ)))
  @inline override val concrete: Const = this
  override def toString: String = name
}

object Const {
  def apply(name: String, typ: Typ)(implicit isabelle: Isabelle, ec: ExecutionContext) = new Const(name, typ)

  @tailrec
  def unapply(term: Term): Option[(String, Typ)] = term match {
    case const : Const => Some((const.name,const.typ))
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}

final class Free private[isabelle] (val name: String, val typ: Typ, val initialMlValue: MLValue[Term]=null)
                                   (implicit val isabelle: Isabelle) extends Term {
  lazy val mlValue : MLValue[Term] = if (initialMlValue!=null) initialMlValue else ???
  @inline override val concrete: Free = this
  override def toString: String = name
}

object Free {
  def apply(name: String, typ: Typ)(implicit isabelle: Isabelle) = new Free(name, typ)

  @tailrec
  def unapply(term: Term): Option[(String, Typ)] = term match {
    case free : Free => Some((free.name,free.typ))
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}

final class Var private(val name: String, val index: Int, val typ: Typ, val initialMlValue: MLValue[Term]=null)
                       (implicit val isabelle: Isabelle) extends Term {
  lazy val mlValue : MLValue[Term] = if (initialMlValue!=null) initialMlValue else ???
  @inline override val concrete: Var = this
  override def toString: String = s"?$name$index"
}

object Var {
  def apply(name: String, index: Int, typ: Typ)(implicit isabelle: Isabelle) = new Var(name, index, typ)

  @tailrec
  def unapply(term: Term): Option[(String, Int, Typ)] = term match {
    case v : Var => Some((v.name,v.index,v.typ))
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}

final class App private (val fun: Term, val arg: Term, val initialMlValue: MLValue[Term]=null)
                        (implicit val isabelle: Isabelle) extends Term {
  lazy val mlValue : MLValue[Term] = if (initialMlValue!=null) initialMlValue else ???
  @inline override val concrete: App = this
  override def toString: String = s"($fun $$ $arg)"
}

object App {
  def apply(fun: Term, arg: Term)(implicit isabelle: Isabelle) = new App(fun, arg)

  @tailrec
  def unapply(term: Term): Option[(Term, Term)] = term match {
    case app : App => Some((app.fun,app.arg))
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}

final class Abs private (val name: String, val typ: Typ, val body: Term, val initialMlValue: MLValue[Term]=null)
                        (implicit val isabelle: Isabelle) extends Term {
  lazy val mlValue : MLValue[Term] = if (initialMlValue!=null) initialMlValue else ???
  @inline override val concrete: Abs = this
  override def toString: String = s"(λ$name. $body)"
}

object Abs {
  def apply(name: String, typ: Typ, body: Term)(implicit isabelle: Isabelle) = new Abs(name,typ,body)

  @tailrec
  def unapply(term: Term): Option[(String, Typ, Term)] = term match {
    case abs : Abs => Some((abs.name,abs.typ,abs.body))
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}


final class Bound private (val index: Int, val initialMlValue: MLValue[Term]=null)
                          (implicit val isabelle: Isabelle) extends Term {
  lazy val mlValue : MLValue[Term] = if (initialMlValue!=null) initialMlValue else ???
  @inline override val concrete: Bound = this
  override def toString: String = s"Bound $index"
}

object Bound {
  def apply(index: Int)(implicit isabelle: Isabelle) = new Bound(index)

  @tailrec
  def unapply(term: Term): Option[Int] = term match {
    case bound : Bound => Some(bound.index)
    case term : MLValueTerm => unapply(term.concrete)
    case _ => None
  }
}



object Term {

  private implicit var isabelle: Isabelle = _
  private var readTerm: MLFunction[((Context, String)), Term] = _
  private var readTermConstrained: MLFunction[(Context, String, Typ), Term] = _
  private var stringOfTerm: MLFunction[(Context, Term), String] = _
  private[isabelle] var dest_App: MLFunction[Term, (Term,Term)] = _
  private[isabelle] var stringOfCterm: MLFunction[(Context, Cterm), String] = _
  private[isabelle] var termOfCterm: MLFunction[Cterm, Term] = _
  private[isabelle] var ctermOfTerm: MLFunction[(Context, Term), Cterm] = _
  private var equalsTerm: MLFunction[(Term,Term), Boolean] = _

  private[isabelle] var whatTerm : MLFunction[Term, Int] = _
  private[isabelle] var termName: MLFunction[Term, String] = _
  private[isabelle] var termTyp: MLFunction[Term, Typ] = _
  private[isabelle] var makeConst: MLFunction[(String, Typ), Term] = _

  // TODO Ugly hack, fails if there are several Isabelle objects
  def init(isabelle: Isabelle)(implicit ec: ExecutionContext): Unit = synchronized {
    if (this.isabelle == null) {
      this.isabelle = isabelle
      implicit val _ = isabelle
      Typ.init(isabelle)
      isabelle.executeMLCodeNow("exception E_Term of term;; exception E_CTerm of cterm")
      readTerm = MLValue.compileFunction[(Context, String), Term]("fn (ctxt, str) => Syntax.read_term ctxt str")
      readTermConstrained = MLValue.compileFunction[(Context, String, Typ), Term]("fn (ctxt,str,typ) => Syntax.parse_term ctxt str |> Type.constraint typ |> Syntax.check_term ctxt")
      stringOfTerm = MLValue.compileFunction[(Context, Term), String]("fn (ctxt, term) => Syntax.string_of_term ctxt term")
      stringOfCterm = MLValue.compileFunction[(Context, Cterm), String]("fn (ctxt, cterm) => Syntax.string_of_term ctxt (Thm.term_of cterm)")
      whatTerm = MLValue.compileFunctionRaw[Term, Int]("fn (E_Term term) => (case term of Const _ => 1 | Free _ => 2 | Var _ => 3 | Bound _ => 4 | Abs _ => 5 | _ $ _ => 6) |> E_Int")
      termName = MLValue.compileFunctionRaw[Term, String]("fn (E_Term term) => (case term of Const (name,_) => name | Free (name,_) => name | Var ((name,_),_) => name | Abs(name,_,_) => name) |> E_String")
      termTyp = MLValue.compileFunctionRaw[Term, Typ]("fn (E_Term term) => (case term of Const (_,typ) => typ | Free (_,typ) => typ | Var (_,typ) => typ | Abs(_,typ,_) => typ) |> E_Typ")
      termOfCterm = MLValue.compileFunctionRaw[Cterm, Term]("fn (E_CTerm cterm) => E_Term (Thm.term_of cterm)")
      ctermOfTerm = MLValue.compileFunction[(Context, Term), Cterm]("fn (ctxt, term) => Thm.cterm_of ctxt term")
      makeConst = MLValue.compileFunction[(String, Typ), Term]("fn (name, typ) => Const (name, typ)")
      dest_App = MLValue.compileFunction[Term, (Term,Term)]("Term.dest_comb")
      equalsTerm = MLValue.compileFunction[(Term,Term), Boolean]("op=")
    }
  }

  def apply(context: Context, string: String)(implicit ec: ExecutionContext): MLValueTerm = {
    new MLValueTerm(readTerm(MLValue((context, string))))
  }

  def apply(context: Context, string: String, typ: Typ)(implicit ec: ExecutionContext): MLValueTerm = {
    new MLValueTerm(readTermConstrained(MLValue((context, string, typ))))
  }

  object TermConverter extends Converter[Term] {
    override def store(value: Term)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[Term] =
      value.mlValue
    override def retrieve(value: MLValue[Term])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[Term] =
      for (_ <- value.id)
        yield new MLValueTerm(mlValue = value)
    override lazy val exnToValue: String = "fn (E_Term t) => t"
    override lazy val valueToExn: String = "E_Term"
  }

  object Implicits {
    implicit val termConverter: TermConverter.type = TermConverter
  }
}
