package qrhl.toplevel

import info.hupel.isabelle.pure
import qrhl.isabelle.{Isabelle, RichTerm}
import qrhl.logic._
import qrhl.tactic._
import info.hupel.isabelle.pure.Typ
import qrhl.{toplevel, _}

import scala.util.parsing.combinator._

case class ParserContext(environment: Environment,
                         isabelle: Option[Isabelle.Context])

object Parser extends JavaTokenParsers {

  val identifier : Parser[String] = """[a-zA-Z][a-zA-Z0-9_']*""".r
  val identifierListSep : Parser[String] = ","
  val identifierList : Parser[List[String]] = rep1sep(identifier,identifierListSep)
  val identifierList0 : Parser[List[String]] = repsep(identifier,identifierListSep)
  val identifierTuple: Parser[List[String]] = "(" ~> identifierList0 <~ ")"
//  val identifierOrTuple: Parser[List[String]] = identifierTuple | (identifier ^^ {s:String => List(s)})

  val identifierAsVarterm: Parser[VTSingle[String]] = identifier ^^ VTSingle[String]
  //noinspection ForwardReference
  val vartermParens : Parser[VarTerm[String]] = "(" ~> varterm <~ ")"
  val vartermAtomic: Parser[VarTerm[String]] = vartermParens | identifierAsVarterm
  val varterm: Parser[VarTerm[String]] =
    rep1sep(vartermAtomic, identifierListSep) ^^ vartermListToVarterm
//  val varterm0 = vartermParens | vartermNoParens0
//  val varterm: toplevel.Parser.Parser[VarTerm[String]] = vartermNoParens1 | vartermParens
  private def vartermListToVarterm(vts:List[VarTerm[String]]) = {
    vts match {
    case Nil => VTUnit
    case _ => vts.foldRight(null:VarTerm[String]) { (a,b) => if (b==null) a else VTCons(a,b) }
  }}
//  val vartermNoParens1 : Parser[VarTerm[String]] =
//    rep1sep(vartermParens | identifierAsVarterm, identifierListSep) ^^ vartermListToVarterm
//  val vartermNoParens0 : Parser[VarTerm[String]] =
//    repsep(vartermParens | identifierAsVarterm, identifierListSep) ^^ vartermListToVarterm

  //  val natural : Parser[BigInt] = """[0-9]+""".r ^^ { BigInt(_) }
  val natural: Parser[Int] = """[0-9]+""".r ^^ { _.toInt }

  val noIsabelleError: String = """Need isabelle command first. Try "isabelle." or "isabelle <theory name>."""""

  private val statementSeparator = literal(";")

  def repWithState[S](p : S => Parser[S], start : S) : Parser[S] =
    p(start).?.flatMap { case Some(s) => repWithState(p,s); case None => success(start) }

  val scanInnerSyntax : Parser[String] = repWithState[(Int,List[Char])]({
    case (0, chars) =>
      elem("expression", { c => c != ';' && c != ')' && c != '}' }) ^^ { c =>
        val cs = c :: chars
        val lvl = if (c == '(' || c == '{') 1 else 0
        (lvl, cs)
      }
    case (level, chars) =>
      elem("expression", { _ => true }) ^^ { c =>
        assert(level > 0)
        val cs = c :: chars
        val lvl = if (c == '(' || c== '{') level + 1 else if (c == ')' || c=='}') level - 1 else level
        (lvl, cs)
      }
  }, (0,Nil)) ^^ { case (_,chars) => chars.reverse.mkString.trim }

  def expression(typ:pure.Typ)(implicit context:ParserContext) : Parser[RichTerm] =
//    rep1 (elem("expression",{c => c!=';'})) ^^ { str:List[_] => context.isabelle match {
    scanInnerSyntax ^^ { str:String => context.isabelle match {
      case None => throw UserException(noIsabelleError)
      case Some(isa) =>
        val e = RichTerm(isa, str  /*str.mkString.trim*/, typ)
        for (v <- e.variables)
          if (!context.environment.variableExists(v))
            throw UserException(s"Variable $v was not declared (in expression $str")
        e
    } }

  private val assignSymbol = literal("<-")
  def assign(implicit context:ParserContext) : Parser[Assign] =
    for (lhs <- varterm;
         _ <- assignSymbol;
         // TODO: add a cut
         lhsV = lhs.map[CVariable] { context.environment.getCVariable };
         typ = Isabelle.tupleT(lhsV.map[Typ](_.valueTyp));
         e <- expression(typ);
         _ <- statementSeparator)
     yield Assign(lhsV, e)

  private val sampleSymbol = literal("<$")
  def sample(implicit context:ParserContext) : Parser[Sample] =
    for (lhs <- varterm;
         _ <- sampleSymbol;
         // TODO: add a cut
         lhsV = lhs.map[CVariable] { context.environment.getCVariable };
         typ = Isabelle.tupleT(lhsV.map[Typ](_.valueTyp));
         e <- expression(Isabelle.distrT(typ));
         _ <- statementSeparator)
      yield Sample(lhsV, e)

  def programExp(implicit context:ParserContext) : Parser[Call] = identifier ~
    (literal("(") ~ rep1sep(programExp,identifierListSep) ~ ")").? ^^ {
    case name ~ args =>
      val args2 = args match { case None => Nil; case Some(_ ~ a ~ _) => a }
      context.environment.programs.get(name) match {
        case None => throw UserException(s"""Program $name not defined (in call-statement).""")
        case Some(decl) =>
          if (decl.numOracles!=args2.length)
            throw UserException(s"""Program $name expects ${decl.numOracles} oracles (in call-statement)""")
      }
      Call(name,args2 : _*)
  }

  def call(implicit context:ParserContext) : Parser[Call] =
    literal("call") ~! programExp ~ statementSeparator ^^ { case _ ~ call ~ _ => call }

  private val qInitSymbol = literal("<q")
  def qInit(implicit context:ParserContext) : Parser[QInit] =
    for (vs <- identifierList;
         _ <- qInitSymbol;
    // TODO: add a cut
         _ = assert(vs.nonEmpty);
         _ = assert(vs.distinct.length==vs.length); // checks if all vs are distinct
         qvs = VarTerm.varlist(vs.map { context.environment.getQVariable }:_*);
         typ = Isabelle.vectorT(Isabelle.tupleT(qvs.map[Typ](_.valueTyp)));
         e <- expression(typ);
         _ <- statementSeparator)
      yield QInit(qvs,e)

  def qApply(implicit context:ParserContext) : Parser[QApply] =
      for (_ <- literal("on");
           vs <- identifierList;
           _ <- literal("apply");
           _ = assert(vs.nonEmpty);
           _ = assert(vs.distinct.length==vs.length); // checks if all vs are distinct
           qvs = VarTerm.varlist(vs.map { context.environment.getQVariable }:_*);
           typ = Isabelle.boundedT(Isabelle.tupleT(qvs.map[Typ](_.valueTyp)));
           e <- expression(typ);
           _ <- statementSeparator)
        yield QApply(qvs,e)

  val measureSymbol : Parser[String] = assignSymbol
  def measure(implicit context:ParserContext) : Parser[Measurement] =
    for (res <- varterm;
         _ <- measureSymbol;
         _ <- literal("measure");
         vs <- varterm;
         resv = res.map[CVariable] { context.environment.getCVariable };
         qvs = vs.map[QVariable] { context.environment.getQVariable };
         _ <- literal("with");
         etyp = Isabelle.measurementT(Isabelle.tupleT(resv.map[Typ](_.valueTyp)), Isabelle.tupleT(qvs.map[Typ](_.valueTyp)));
         e <- expression(etyp);
         _ <- statementSeparator)
      yield Measurement(resv,qvs,e)

  def ifThenElse(implicit context:ParserContext) : Parser[IfThenElse] =
    for (_ <- literal("if");
         _ <- literal("(");
         e <- expression(Isabelle.boolT);
         _ <- literal(")");
         _ <- literal("then");
         thenBranch <- statementOrParenBlock; // TODO: allow nested block
         _ <- literal("else");
         elseBranch <- statementOrParenBlock)  // TODO: allow nested block
      yield IfThenElse(e,thenBranch,elseBranch)

  def whileLoop(implicit context:ParserContext) : Parser[While] =
    for (_ <- literal("while");
         _ <- literal("(");
         e <- expression(Isabelle.boolT);
         _ <- literal(")");
         body <- statementOrParenBlock)
      yield While(e,body)

  def statement(implicit context:ParserContext) : Parser[Statement] = measure | assign | sample | call | qInit | qApply | ifThenElse | whileLoop

//  def statementWithSep(implicit context:ParserContext) : Parser[Statement] = statement ~ statementSeparator ^^ { case s ~ _ => s }

  def skip : Parser[Block] = "skip" ~! statementSeparator ^^ { _ => Block.empty }

  def statementOrParenBlock(implicit context:ParserContext) : Parser[Block] =
    parenBlock | skip | (statement ^^ { s => Block(s) })

  def parenBlock(implicit context:ParserContext) : Parser[Block] =
    ("{" ~ "}" ^^ { _ => Block() }) |
    ("{" ~> block <~ "}")

  def block(implicit context:ParserContext) : Parser[Block] =
    (statementSeparator ^^ { _ => Block.empty }) |
      (rep1(statement) ^^ { s => Block(s:_*) }) |
      skip

  // TODO does not match strings like "xxx\"xxx" or "xxx\\xxx" properly
  val quotedString : Parser[String] = stringLiteral ^^ { s:String =>
    val result = new StringBuilder()
    val iterator = s.substring(1,s.length-1).iterator
    for (c <- iterator)
      if (c=='\\') result.append(iterator.next())
      else result.append(c)
    result.toString()
  }

  val unquotedStringNoComma : Parser[String] = "[^.,]+".r

//  val commandEndSymbol : Parser[_] = literal(".")
  val isabelle : Parser[IsabelleCommand] =
    literal("isabelle") ~> identifierList0 ^^ IsabelleCommand

  def typ(implicit context:ParserContext) : Parser[pure.Typ] =
  //    rep1 (elem("expression",{c => c!=';'})) ^^ { str:List[_] => context.isabelle match {
    scanInnerSyntax ^^ { str:String => context.isabelle match {
      case None => throw UserException(noIsabelleError)
      case Some(isa) => isa.readTypUnicode(str)
    } }

  def variableKind : Parser[String] = "classical|quantum|ambient".r
  //noinspection RedundantDefaultArgument
  def variable(implicit context:ParserContext) : Parser[DeclareVariableCommand] =
    variableKind ~ OnceParser(literal("var") ~ identifier ~ literal(":") ~ typ) ^^ {
      case kind~(_~id~_~typ) => kind match {
        case "classical" => DeclareVariableCommand(id,typ,quantum=false)
        case "quantum" => DeclareVariableCommand(id,typ,quantum=true)
        case "ambient" => DeclareVariableCommand(id,typ,ambient=true)
      }
    }

//  def quantumVariable(implicit context:ParserContext) : Parser[DeclareVariableCommand] =
//    literal("quantum") ~> literal("var") ~> OnceParser(identifier ~ literal(":") ~ typ) ^^ { case id~_~typ =>
//      DeclareVariableCommand(id,typ,quantum=true)
//    }

  def declareProgram(implicit context:ParserContext) : Parser[DeclareProgramCommand] =
    literal("program") ~> OnceParser(for (
      name <- identifier;
      args <- identifierTuple.?;
      args2 = args.getOrElse(Nil);
      _ <- literal(":=");
      // temporarily add oracles to environment to allow them to occur in call-expressions during parsing
      context2 = args2.foldLeft(context) { case (ctxt,p) => ctxt.copy(ctxt.environment.declareProgram(AbstractProgramDecl(p,Nil,Nil,0))) };
      body <- parenBlock(context2))
      yield DeclareProgramCommand(name,args2,body))

  private def declareAdversaryCalls: Parser[Int] = (literal("calls") ~ rep1sep(literal("?"),identifierListSep)).? ^^ {
    case None => 0
    case Some(_ ~ progs) => progs.length
  }

  def declareAdversary(implicit context:ParserContext) : Parser[DeclareAdversaryCommand] =
    literal("adversary") ~> OnceParser(identifier ~ literal("vars") ~ identifierList ~ declareAdversaryCalls) ^^ {
      case name ~ _ ~ vars ~ calls =>
        for (v <- vars) if (!context.environment.cVariables.contains(v) && !context.environment.qVariables.contains(v))
          throw UserException(s"Not a program variable: $v")
        val cvars = vars.flatMap(context.environment.cVariables.get)
        val qvars = vars.flatMap(context.environment.qVariables.get)
        DeclareAdversaryCommand(name,cvars,qvars,calls)
    }

  def goal(implicit context:ParserContext) : Parser[GoalCommand] =
    literal("lemma") ~> OnceParser((identifier <~ ":").? ~ expression(Isabelle.boolT)) ^^ {
      case name ~ e => GoalCommand(name.getOrElse(""), AmbientSubgoal(e)) }

  def qrhl(implicit context:ParserContext) : Parser[GoalCommand] =
  literal("qrhl") ~> OnceParser(for (
    name <- (identifier <~ ":").?;
    _ <- literal("{");
    pre <- expression(Isabelle.predicateT);
    _ <- literal("}");
    left <- block;
    _ <- literal("~");
    right <- block;
    _ <- literal("{");
    post <- expression(Isabelle.predicateT);
    _ <- literal("}")
  ) yield GoalCommand(name.getOrElse(""), QRHLSubgoal(left,right,pre,post,Nil)))

  val tactic_wp: Parser[WpTac] =
    literal("wp") ~> {
      (literal("left") ~> natural.? ^^ { n => WpTac(left = n.getOrElse(1), right = 0) }) |
        (literal("right") ~> natural.? ^^ { n => WpTac(right = n.getOrElse(1), left = 0) }) |
        (natural ~ natural ^^ { case l ~ r => WpTac(left = l, right = r) })
    }

  val tactic_squash: Parser[SquashTac] =
    literal("squash") ~> OnceParser("left|right".r) ^^ {
      case "left" => SquashTac(left=true)
      case "right" => SquashTac(left=false)
    }

  val swap_range: Parser[SwapTac.Range] =
    (natural ~ "-" ~ natural) ^^ { case i~_~j => SwapTac.MiddleRange(i,j) } |
      natural ^^ SwapTac.FinalRange |
      success(SwapTac.FinalRange(1))

  val tactic_swap: Parser[SwapTac] =
    literal("swap") ~> OnceParser(for (
      lr <- "left|right".r;
      left = lr match { case "left" => true; case "right" => false; case _ => throw new InternalError("Should not occur") };
      range <- swap_range;
      steps <- natural)
//      (numStatements,steps) <- natural~natural ^^ { case x~y => (x,y) } | (natural ^^ { (1,_) }) | success((1,1)))
      yield SwapTac(left=left, range=range, steps=steps))

  val tactic_inline: Parser[InlineTac] =
    literal("inline") ~> identifier ^^ InlineTac

  def tactic_seq(implicit context:ParserContext): Parser[SeqTac] =
    literal("seq") ~> OnceParser(for (
      left <- natural;
      right <- natural;
      _ <- literal(":");
      inner <- expression(Isabelle.predicateT))
      yield SeqTac(left,right,inner))

  def tactic_conseq(implicit context:ParserContext): Parser[ConseqTac] =
    literal("conseq") ~> OnceParser("pre|post".r ~ literal(":") ~ expression(Isabelle.predicateT)) ^^ {
      case "pre" ~ _ ~ e => ConseqTac(pre=Some(e))
      case "post" ~ _ ~ e => ConseqTac(post=Some(e))
    }

  def tactic_equal(implicit context:ParserContext) : Parser[EqualTac] =
    literal("equal") ~> (literal("exclude") ~> identifierList).? ~ (literal("qvars") ~> identifierList).? ^^ {
      case exclude ~ qvars =>
        val exclude2 = exclude.getOrElse(Nil)
        for (p <- exclude2 if !context.environment.programs.contains(p))
          throw UserException(s"Undeclared program $p")

        val qvars2 = qvars.getOrElse(Nil) map { context.environment.getQVariable }

        EqualTac(exclude=exclude2, qvariables = qvars2)
    }

  def tactic_rnd(implicit context:ParserContext): Parser[Tactic] =
    literal("rnd") ~> (for (
      x <- vartermAtomic;
      xVar = x.map[CVariable](context.environment.getCVariable);
      xTyp = Isabelle.tupleT(xVar.map[Typ](_.valueTyp));
      _ <- literal(",");
      y <- vartermAtomic;
      yVar = y.map[CVariable](context.environment.getCVariable);
      yTyp = Isabelle.tupleT(yVar.map[Typ](_.valueTyp));
      _ <- sampleSymbol | assignSymbol;
      e <- expression(Isabelle.distrT(Isabelle.prodT(xTyp,yTyp)))
    ) yield (xVar,yVar,e)).? ^^ {
      case None => RndEqualTac
      case Some((xVar,yVar,e)) => RndWitnessTac(xVar,yVar,e)
    }

  def tactic_case(implicit context:ParserContext): Parser[CaseTac] =
    literal("case") ~> OnceParser(for (
      x <- identifier;
      xT = context.environment.getAmbientVariable(x);
      _ <- literal(":=");
      e <- expression(xT)
    ) yield CaseTac(x,e))

  val tactic_simp : Parser[SimpTac] =
    literal("simp") ~> OnceParser("[!\\*]".r.? ~ rep(identifier)) ^^ {
      case None ~ lemmas => SimpTac(lemmas, force=false, everywhere = false)
      case Some("!") ~ lemmas => SimpTac(lemmas, force = true, everywhere = false)
      case Some("*") ~ lemmas => SimpTac(lemmas, force = false, everywhere = true)
      case _ => throw new RuntimeException("Internal error") // cannot happen
    }

  def tactic_rule(implicit context:ParserContext) : Parser[RuleTac] = {
    val inst: toplevel.Parser.Parser[(String, RichTerm)] = (identifier <~ literal(":=")) ~ expression(Isabelle.dummyT) ^^ { case a~b => (a,b) }

    literal("rule") ~> OnceParser(for (
      rule <- identifier;
      instantiations <- (literal("where") ~> rep1sep(inst, statementSeparator)).?
    ) yield RuleTac(rule, instantiations.getOrElse(Nil)))
  }

  val tactic_clear : Parser[ClearTac] =
    literal("clear") ~> OnceParser(natural) ^^ ClearTac.apply

  def tactic_split(implicit context:ParserContext) : Parser[CaseSplitTac] =
    literal("casesplit") ~> OnceParser(expression(Isabelle.boolT)) ^^ CaseSplitTac

  def tactic_fix : Parser[FixTac] =
    literal("fix") ~> identifier ^^ FixTac

  def tactic(implicit context:ParserContext): Parser[Tactic] =
    literal("admit") ^^ { _ => Admit } |
      tactic_wp |
      tactic_swap |
      tactic_simp |
      tactic_rule |
      tactic_clear |
      literal("skip") ^^ { _ => SkipTac } |
//      literal("true") ^^ { _ => TrueTac } |
      tactic_inline |
      tactic_seq |
      tactic_conseq |
      literal("call") ^^ { _ => ErrorTac("Call tactic was renamed. Use \"equal\" instead.") } |
      tactic_equal |
      tactic_rnd |
      literal("byqrhl") ^^ { _ => ByQRHLTac } |
      tactic_split |
      tactic_case |
      tactic_fix |
      tactic_squash |
      literal("measure") ^^ { _ => JointMeasureTac } |
      literal("o2h") ^^ { _ => O2HTac } |
      literal("semiclassical") ^^ { _ => SemiClassicalTac }

  val undo: Parser[UndoCommand] = literal("undo") ~> natural ^^ UndoCommand

  val qed : Parser[QedCommand] = "qed" ^^ { _ => QedCommand() }

//  val quit: Parser[QuitCommand] = "quit" ^^ { _ => QuitCommand() }

  val debug : Parser[DebugCommand] = "debug:" ~>
    ("goal" ^^ { _ => DebugCommand.goals((context,goals) => for (g <- goals) println(g.toTerm(context))) } |
      "isabelle" ^^ { _ => DebugCommand.isabelle })

  val changeDirectory : Parser[ChangeDirectoryCommand] = literal("changeDirectory") ~> quotedString ^^ ChangeDirectoryCommand.apply

  val cheat : Parser[CheatCommand] =
    ("cheat:file" ^^ {_ => CheatCommand(file=true)}) |
      ("cheat:proof" ^^ {_ => CheatCommand(proof=true)}) |
      ("cheat:stop" ^^ {_ => CheatCommand(stop=true)}) |
      ("cheat" ^^ {_ => CheatCommand()})

  val include : Parser[IncludeCommand] =
    "include" ~> quotedString ^^ IncludeCommand.apply

  def command(implicit context:ParserContext): Parser[Command] =
    (debug | isabelle | variable | declareProgram | declareAdversary | qrhl | goal | (tactic ^^ TacticCommand) |
      include | undo | qed | changeDirectory | cheat).named("command")
}
