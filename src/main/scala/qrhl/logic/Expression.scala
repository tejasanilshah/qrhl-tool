package qrhl.logic

import info.hupel.isabelle.api.XML
import info.hupel.isabelle.{Codec, Operation, XMLResult, pure}
import info.hupel.isabelle.hol.HOLogic
import info.hupel.isabelle.hol.HOLogic.boolT
import info.hupel.isabelle.pure.{Abs, App, Bound, Const, Free, TFree, TVar, Term, Type, Var, Typ => ITyp}
import qrhl.isabelle.Isabelle
import scalaz.Applicative

import scala.collection.mutable
import Expression.typ_tight_codec
import Expression.term_tight_codec
import org.log4s
import org.log4s.Logger


// TODO: move into Isabelle
final class Expression private (val typ: pure.Typ, val isabelleTerm:Term, val pretty:Option[String]=None) {
  def encodeAsExpression(context: Isabelle.Context) : Expression =
    context.isabelle.invoke(Expression.termToExpressionOp, (context.contextId, isabelleTerm))

  def stripAssumption(number: Int): Expression = Expression(typ,Expression.stripAssumption(isabelleTerm,number))

  override def equals(o: scala.Any): Boolean = o match {
    case o : Expression => typ == o.typ && isabelleTerm == o.isabelleTerm
    case _ => false
  }

  def checkWelltyped(context:Isabelle.Context, ityp:ITyp): Unit = {
    assert(ityp==this.typ,s"$ityp != ${this.typ}")
    assert(context.checkType(isabelleTerm) == typ)
  }

  /** Free variables */
  private def freeVars(term: Term): Set[String] = {
    val fv = new mutable.SetBuilder[String,Set[String]](Set.empty)
    def collect(t:Term) : Unit = t match {
      case Free(v,_) => fv += v
//      case App(App(App(Const(Isabelle.probability_old.name,_),t1),t2),t3) =>
//        fv += Isabelle.dest_string(t1)
//        collect(t2); collect(t3)
      case Const(_,_) | Bound(_) | Var(_,_) =>
      case App(t1,t2) => collect(t1); collect(t2)
      case Abs(_,_,body) => collect(body)
    }
    collect(term)
    fv.result
  }

  def variables : Set[String] = freeVars(isabelleTerm)

  /** Finds all classical and ambient variables in an expression. The expression is assumed not to have indexed variables. */
  def caVariables(environment: Environment, cvars : mutable.Set[CVariable], avars : mutable.Set[String]): Unit = {
//    val cvars = mutable.LinkedHashSet[CVariable]()
//    val avars = mutable.LinkedHashSet[String]()
    for (v<-variables) environment.cVariables.get(v) match {
      case Some(cv) => cvars += cv
      case None => avars += v
    }
  }

  override lazy val toString: String = pretty match {
    case Some(s) => s
    case _ => Isabelle.theContext.prettyExpression(isabelleTerm)
  }

//  val isabelleTerm : Term = isabelleTerm
  def simplify(isabelle: Option[Isabelle.Context], facts:List[String]): (Expression,Isabelle.Thm) = simplify(isabelle.get,facts)

  def simplify(context: Isabelle.Context, facts:List[String]): (Expression,Isabelle.Thm) =
    context.simplify(isabelleTerm,facts)/* match {
      case (t,thm) => (Expression(typ, t), thm)
    }*/

  def map(f : Term => Term) : Expression = new Expression(typ, f(isabelleTerm))
  def substitute(v:CVariable, repl:Expression) : Expression = {
    assert(repl.typ==v.valueTyp)
    map(Expression.substitute(v.name, repl.isabelleTerm, _))
  }

  def index1(environment: Environment): Expression = index(environment, left=true)
  def index2(environment: Environment): Expression = index(environment, left=false)
  def index(environment: Environment, left: Boolean): Expression = {
    def idx(t:Term) : Term = t match {
      case App(t1,t2) => App(idx(t1),idx(t2))
      case Free(name,typ2) =>
        if (environment.ambientVariables.contains(name)) t
        else Free(Variable.index(left=left,name), typ2)
      case Const(_,_) | Bound(_) | Var(_,_) => t
      case Abs(name,typ2,body) => Abs(name,typ2,idx(body))
    }
    new Expression(typ,idx(isabelleTerm))
  }


  def leq(e: Expression): Expression = {
      val t = e.isabelleTerm
      val predicateT = Isabelle.predicateT // Should be the type of t
      val newT =  Const ("Orderings.ord_class.less_eq", ITyp.funT(predicateT, ITyp.funT(predicateT, boolT))) $ isabelleTerm $ t
      new Expression(Isabelle.boolT,newT)
  }

  def implies(e: Expression): Expression = {
    val t = e.isabelleTerm
    val newT = HOLogic.imp $ isabelleTerm $ t
//    val typ = Typ.bool(null)
    new Expression(Isabelle.boolT,newT)
  }

  def not: Expression = {
    assert(typ==HOLogic.boolT)
    new Expression(typ,Const("HOL.Not",HOLogic.boolT -->: HOLogic.boolT) $ isabelleTerm)
  }

}


object Expression {
  private val logger: Logger = log4s.getLogger

  implicit object applicativeXMLResult extends Applicative[XMLResult] {
    override def point[A](a: => A): XMLResult[A] = Right(a)
    override def ap[A, B](fa: => XMLResult[A])(f: => XMLResult[A => B]): XMLResult[B] = fa match {
      case Left(error) => Left(error)
      case Right(a) => f match {
        case Left(error) => Left(error)
        case Right(ab) => Right(ab(a))
      }
    }
  }

  implicit object typ_tight_codec extends Codec[ITyp] {
    override val mlType: String = "term"

    def decode_class(tree: XML.Tree): XMLResult[String] = tree match {
      case XML.Elem((c,Nil),Nil) => Right(c)
    }
    def encode_class(c : String): XML.Tree = XML.Elem((c,Nil),Nil)

    override def encode(t: ITyp): XML.Tree = t match {
      case Type(name, typs) => XML.Elem(("t", Nil), XML.Text(name) :: typs.map(encode))
      case TFree(name, sort) => XML.Elem(("f", Nil), XML.Text(name) :: sort.map(encode_class))
      case TVar((name, idx), sort) => XML.Elem(("v", List((name, idx.toString))), sort.map(encode_class))
    }

    import scalaz._, std.list._, std.option._, syntax.traverse._

    override def decode(tree: XML.Tree): XMLResult[ITyp] = tree match {
      case XML.Elem(("t",Nil), XML.Text(name) :: xmls) =>
        for (ts <- xmls.map(decode).sequence) yield Type(name,ts)
      case XML.Elem (("f",Nil), XML.Text(name) :: xmls) =>
        for (sort <- xmls.map(decode_class).sequence) yield TFree(name,sort)
      case xml @ XML.Elem(("v",List((name,idx))), xmls) =>
//        try {
        val i = Integer.parseInt(idx)
        for (sort <- xmls.map(decode_class).sequence) yield TVar((name,i),sort)
//        } catch {
//          case e : NumberFormatException =>
//            Right(())
//        }
      case xml =>
        logger.debug(xml.toString)
        Left(("invalid encoding for a type",List(xml)))
    }
  }

  implicit object term_tight_codec extends Codec[Term] {
    override val mlType: String = "term"

    override def encode(t: Term): XML.Tree = t match {
      case Const(name, typ) => XML.Elem(("c", Nil), List(XML.Text(name), typ_tight_codec.encode(typ)))
      case App(t1, t2) => XML.Elem(("a", Nil), List(encode(t1), encode(t2)))
      case Free(name, typ) => XML.Elem(("f", Nil), List(XML.Text(name), typ_tight_codec.encode(typ)))
      case Var((name, idx), typ) => XML.Elem(("v", List((name, idx.toString))), List(typ_tight_codec.encode(typ)))
      case Abs(name, typ, body) => XML.Elem(("A", Nil), List(XML.Text(name), typ_tight_codec.encode(typ), encode(body)))
      case Bound(i) => XML.Elem(("b", Nil), List(XML.Text(i.toString)))
    }


    override def decode(tree: XML.Tree): XMLResult[Term] = tree match {
      case XML.Elem(("c",Nil),List(XML.Text(name),typXml)) =>
        for (typ <- typ_tight_codec.decode(typXml)) yield Const(name,typ)

      case XML.Elem(("a",Nil),List(xml1, xml2)) =>
        for (t1 <- decode(xml1);
             t2 <- decode(xml2))
          yield t1 $ t2

      case XML.Elem(("f",Nil), List(XML.Text(name), xml)) =>
        for (typ <- typ_tight_codec.decode(xml))
          yield Free(name,typ)

      case XML.Elem(("v",List((name,idx))), List(xml1)) =>
        val i = Integer.parseInt(idx)
        for (typ <- typ_tight_codec.decode(xml1))
          yield Var((name,i),typ)

      case XML.Elem(("A",Nil), List(XML.Text(name), xmlTyp, xmlBody)) =>
        for (typ <- typ_tight_codec.decode(xmlTyp);
             body <- decode(xmlBody))
          yield Abs(name,typ,body)

      case XML.Elem(("A",Nil), List(xmlTyp, xmlBody)) =>
        for (typ <- typ_tight_codec.decode(xmlTyp);
             body <- decode(xmlBody))
          yield Abs("",typ,body)

      case XML.Elem (("b",Nil), List(XML.Text(idx))) =>
        val i = Integer.parseInt(idx)
        Right(Bound(i))

      case xml =>
        logger.debug(xml.toString)
        Left(("invalid encoding for a term",List(xml)))
    }
  }

  implicit object codec extends Codec[Expression] {
    override val mlType: String = "term"
    /*override def encode(e: Expression): XML.Tree =
      XML.elem(("expression",Nil),
        List(XML.text(""), term_tight_codec.encode(e.isabelleTerm), XML.elem(("omitted",Nil),Nil)))*/
    override def encode(e: Expression): XML.Tree = throw new RuntimeException("Do not call this!")
    override def decode(tree: XML.Tree): XMLResult[Expression] = tree match {
      case XML.Elem(("expression",Nil), List(XML.Text(str), termXML, typXML)) =>
        for (typ <- typ_tight_codec.decode(typXML);
             term <- term_tight_codec.decode(termXML))
        yield new Expression(typ,term,Some(Isabelle.symbolsToUnicode(str)))
    }
  }

  def decodeFromExpression(context:Isabelle.Context, t: Term): Expression = {
    context.isabelle.invoke(decodeFromExpressionOp, (context.contextId, t))
//    Expression(typ, term)
  }

  val decodeFromExpressionOp: Operation[(BigInt,Term), Expression] =
    Operation.implicitly[(BigInt,Term), Expression]("expression_to_term")

  val termToExpressionOp: Operation[(BigInt, Term), Expression] =
    Operation.implicitly[(BigInt, Term), Expression]("term_to_expression")

  def trueExp(isabelle: Isabelle.Context): Expression = Expression(Isabelle.boolT, HOLogic.True)

  private val readExpressionOp : Operation[(BigInt, String, ITyp), Expression] = Operation.implicitly[(BigInt, String, ITyp), Expression]("read_expression")
  def apply(context: Isabelle.Context, str:String, typ:pure.Typ) : Expression = {
    context.isabelle.invoke(readExpressionOp,(context.contextId,Isabelle.unicodeToSymbols(str),typ))
//    val term = context.readTerm(Isabelle.unicodeToSymbols(str),typ)
//    Expression(typ, term)
  }

  def apply(typ: pure.Typ, term:Term) : Expression =
    new Expression(typ, term)

  def unapply(e: Expression): Option[Term] = Some(e.isabelleTerm)

  def substitute(v: String, repl: Term, term: Term): Term = {
      def subst(t:Term) : Term = t match {
        case App(t1,t2) => App(subst(t1),subst(t2))
        case Free(name, _) =>
          if (v==name) repl else t
        case Const(_,_) | Bound(_) | Var(_,_) => t
        case Abs(name,typ,body) => Abs(name,typ,subst(body))
      }
      subst(term)
  }

  def stripAssumption(term:Term,number:Int) : Term = term match {
    case App(App(Const("HOL.implies",_),assm0),rest) =>
      assert(number>=0)
      if (number==0) rest
      else
        HOLogic.imp $ assm0 $ stripAssumption(rest,number-1)
    case _ => throw qrhl.UserException("Not enough assumptions")
  }
}
