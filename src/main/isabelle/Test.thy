theory Test
  imports Tactics
begin

instantiation expression :: (ord) ord begin
instance sorry
end


ML{*
Encoding.clean_expression_abs_pat_conv @{context} @{cterm "expression \<lbrakk>var_w, var_v, var_w\<rbrakk>
           (\<lambda>x. case case x of (x, q) \<Rightarrow> (x, case q of (x, q) \<Rightarrow> (x, (), q, ())) of (q, r, s) \<Rightarrow> e ((q, r), s)) 
"}
*}


ML {*
Encoding.clean_expression_conv @{context} @{cterm "expression (variable_concat \<lbrakk>var_w,var_w\<rbrakk>
 (variable_concat variable_unit (variable_concat \<lbrakk>var_w\<rbrakk> variable_unit))) e"}
*}

ML {*
Encoding.clean_expression_conv @{context} @{cterm "expression \<lbrakk>var_w, var_v, var_w\<rbrakk>
           (\<lambda>x. case case x of (x, q) \<Rightarrow> (x, case q of (x, q) \<Rightarrow> (x, (), q, ())) of (q, r, s) \<Rightarrow> e ((q, r), s)) 

"}
*}

(*
(* TODO: to Encoding *)
lemma tmp3[simp]: 
  fixes e :: "('a*'b)*'c \<Rightarrow> 'e"
  defines "f == (\<lambda>(a,b,c). e ((a,b),c))"
  shows "expression (variable_concat (variable_concat S Q) R) e
       = expression (variable_concat S (variable_concat Q R)) f"
  sorry
*)

(*
(* TODO: to Encoding *)
lemma tmp4[simp]: 
  fixes e :: "'a*unit \<Rightarrow> 'e"
  shows "expression (variable_concat Q \<lbrakk>\<rbrakk>) e = expression Q (\<lambda>a. e (a,()))"
  sorry
 *)
lemma subst_expression_unit_aux:
  "subst_expression (substitute1 x f) (expression \<lbrakk>\<rbrakk> e') \<equiv> (expression \<lbrakk>\<rbrakk> e')" sorry

lemma subst_expression_singleton_same_aux:
  "subst_expression (substitute1 x (expression R f')) (expression \<lbrakk>x\<rbrakk> e') \<equiv> (expression R (\<lambda>r. e' (f' r)))" sorry

lemma subst_expression_singleton_notsame_aux:
  assumes "variable_name x \<noteq> variable_name y"
  shows "subst_expression (substitute1 x f) (expression \<lbrakk>y\<rbrakk> e') \<equiv> expression \<lbrakk>y\<rbrakk> e'" sorry

lemma subst_expression_concat_id_aux:
  assumes "subst_expression (substitute1 x f) (expression Q1 (\<lambda>x. x)) \<equiv> expression Q1' e1"
  assumes "subst_expression (substitute1 x f) (expression Q2 (\<lambda>x. x)) \<equiv> expression Q2' e2"
  shows "subst_expression (substitute1 x f) (expression (variable_concat Q1 Q2) (\<lambda>x. x)) \<equiv>
    expression (variable_concat Q1' Q2') (\<lambda>(x1,x2). (e1 x1, e2 x2))"
  sorry

(* lemma subst_expression_concat_id:
  assumes "subst_expression (substitute1 x f) (expression Q1 (\<lambda>x. x)) = expression Q1' e1"
  assumes "subst_expression (substitute1 x f) (expression Q2 (\<lambda>x. x)) = expression Q2' e2"
  shows "subst_expression (substitute1 x f) (expression (variable_concat Q1 Q2) (\<lambda>x. x)) =
    expression (variable_concat Q1' Q2') (\<lambda>(x1,x2). (e1 x1, e2 x2))"
  sorry *)

lemma subst_expression_id_comp_aux:
  assumes "subst_expression (substitute1 x f) (expression Q (\<lambda>x. x)) \<equiv> expression Q' g"
  shows "subst_expression (substitute1 x f) (expression Q e) \<equiv> expression Q' (\<lambda>x. e (g x))"
  sorry



(* lemma subst_expression_id_comp_aux:
  assumes "NO_MATCH e (\<lambda>x. x)"
  assumes "subst_expression (substitute1 x f) (expression Q (\<lambda>x. x)) \<equiv> expression Q' g"
  shows "subst_expression (substitute1 x f) (expression Q e) \<equiv> expression Q' (\<lambda>x. e (g x))"
  sorry *)

(*lemma subst_expression_cons_same_aux:
  assumes "subst_expression (substitute1 x f) (expression Q (\<lambda>x. x)) = expression Q'"
  shows "subst_expression (substitute1 x f) (expression (variable_concat \<lbrakk>x\<rbrakk> Q) (\<lambda>x. x)) \<equiv> xxxxx"*)

ML {*
fun subst_expression_conv_tac1 ctxt =
  resolve_tac ctxt @{thms subst_expression_concat_id_aux subst_expression_singleton_same_aux subst_expression_unit_aux} 1
  ORELSE
  CHANGED (resolve_tac ctxt @{thms subst_expression_id_comp_aux} 1)
  ORELSE 
  (resolve_tac ctxt @{thms subst_expression_singleton_notsame_aux} 1
        THEN Misc.SOLVE1 (simp_tac ctxt 1))

fun subst_expression_conv_tac ctxt = REPEAT_DETERM (subst_expression_conv_tac1 ctxt)
*}

(* variables classical v :: int and classical w :: nat begin *)

ML {*

fun subst_expression_conv_noclean_check (_:Proof.context) t = let
  val (sub,e) = case t of 
    Const(@{const_name subst_expression},_) $ sub $ e => (sub,e)
    | _ => raise TERM("not a subst_expression term",[t])
  val (x,_) = case sub of
    Const(@{const_name substitute1},_) $ x $ f => (x,f)
    | _ => raise TERM("not an explicit substitution (substitute1 x f)",[t,sub])
  val (Q,_) = case e of
    Const(@{const_name expression},_) $ Q $ e' => (Q,e')
    | _ => raise TERM("not an explicit expression (substitute1 Q e)",[t,e])
  val _ = QRHL.parse_varlist Q
  val _ = case x of
    Free _ => ()
    | _ => raise TERM("not an explicit variable name",[t,x])
in
  ()
end

val subst_expression_conv_noclean = Misc.conv_from_tac subst_expression_conv_noclean_check subst_expression_conv_tac

fun subst_expression_conv ctxt = subst_expression_conv_noclean ctxt then_conv Encoding.clean_expression_conv ctxt

(* ;;
subst_expression_conv @{context} @{cterm "subst_expression (substitute1 var_v (const_expression z)) (expression \<lbrakk>var_v,var_w\<rbrakk> e)"} *)
*}

(* schematic_goal bla: "subst_expression (substitute1 var_v (const_expression z)) (expression \<lbrakk>var_v,var_w\<rbrakk> e) \<equiv> ?expression"
  (* apply (tactic \<open>REPEAT_DETERM (CHANGED (resolve_tac @{context} @{thms subst_expression_concat_id_meta subst_expression_singleton_same_metaeq subst_expression_id_comp_meta} 1))\<close>) *)
  by (tactic \<open>subst_expression_conv_tac @{context}\<close>)
  (* apply (rule subst_expression_id_comp_meta) *)
  (* apply (rule subst_expression_concat_id_meta) *)
  (* apply (rule subst_expression_singleton_same_metaeq) *)
  (* apply (tactic \<open>\<close>) *)
  (* apply (rule subst_expression_singleton_notsame_metaeq) *)
  (* by simp *) *)

(* lemma xxx: "f \<equiv> (\<lambda>(x,y,z). f (x,y,z))" by auto *)

schematic_goal blu: "expression (variable_concat \<lbrakk>var_w,var_w\<rbrakk> (variable_concat variable_unit (variable_concat \<lbrakk>var_w\<rbrakk> variable_unit))) (\<lambda>x. e (case x of (x1, x2) \<Rightarrow> (z, x2))) \<equiv> ?expression"
  apply (tactic \<open>Encoding.clean_expression_conv_varlist_tac @{context}\<close>)
  done


ML {*
  fun subst_expression_simproc _ ctxt ct = SOME (subst_expression_conv ctxt ct) handle CTERM _ => NONE
*}

simproc_setup subst_expression ("subst_expression (substitute1 x (expression R f')) (expression Q e')") = subst_expression_simproc


variables
  classical x :: int and
  classical a :: int and
  classical b :: int and 
  classical c :: int
begin


lemma qrhl_top: "qrhl A p1 p2 (expression Q (\<lambda>z. top))" sorry



ML {*
subst_expression_conv @{context} @{cterm "subst_expression (substitute1 var_x2 (const_expression 0))
 (expression \<lbrakk>var_x1, var_x2\<rbrakk> (\<lambda>(x1, x2). \<CC>\<ll>\<aa>[Ball {0..max x1 0} (op \<le> x2) ] ))"}
*}

lemma
  assumes [simp]: "x\<ge>0"
  shows "qrhl D [s1,sample var_x Expr[ uniform {0..max x 0}] ] [t1,t2,assign var_x Expr[0] ] Expr[ Cla[x1\<ge>x2] ]"
  using [[method_error,show_types]]
  apply (tactic \<open>Tactics.wp_tac @{context} true 1\<close>)
  apply (tactic \<open>Tactics.wp_tac @{context} false 1\<close>)
  apply simp
  by (rule qrhl_top)


lemma skip:
  assumes "A \<le> B"
  shows "qrhl A [] [] B"
  sorry

lemma expression_leq:
  assumes "\<And>x. e x \<le> e' x"
  shows "expression Q e \<le> expression Q e'"
  sorry

schematic_goal
  assumes [simp]: "x\<ge>0"
  shows "qrhl Expr[ Cla[x1=0 \<and> x2=0] ] [sample var_x Expr[ uniform {0..max x 0}] ] [] Expr[ Cla[x1\<ge>x2] ]"
  using [[method_error,show_types]]
  apply (tactic \<open>Tactics.wp_tac @{context} true 1\<close>)
  apply (rule skip)
  apply simp
  apply (rule expression_leq)
  by auto

schematic_goal "qrhl ?pre [assign var_x Expr[x+2], assign var_x Expr[0], assign var_x Expr[x+1] ] [] Expr[ Cla[x1=1] ]"
  apply (tactic \<open>Tactics.wp_tac @{context} true 1\<close>, simp?)+
  apply (rule qrhl_top)
  oops


end

end
