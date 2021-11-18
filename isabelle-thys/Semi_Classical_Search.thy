theory Semi_Classical_Search
  imports Programs O2H
begin

lemma semi_classical_search:
  fixes q :: nat and b :: "bit cvariable" and rho :: program_state and count :: "nat cvariable"
    and Find :: "bool cvariable" and distr :: "_ distr"
    and z :: "_ cvariable" and G :: "('a::finite \<Rightarrow> 'b::{finite,xor_group}) cvariable" (* TODO back to infinite *) and S :: "'a set cvariable"
    and adv :: oracle_program and X :: "'a qvariable" and Y :: "'b qvariable"
    and in_S :: "bit cvariable" and Count :: "oracle_program"
    and stop_at :: "nat cvariable" and "guess" :: "'a cvariable"
    and localsC :: "'c cvariable" and localsQ :: "'d qvariable"

  assumes "game_left  = block [assign \<lbrakk>count\<rbrakk> Expr[0], sample \<lbrakk>S,G,z\<rbrakk> Expr[distr], 
                        assign \<lbrakk>var_Find\<rbrakk> Expr[False],
                        localvars localsC localsQ [instantiateOracles adv [instantiateOracles Count [queryGS]]]]"
  assumes "game_right = (block [assign \<lbrakk>count\<rbrakk> Expr[0], sample \<lbrakk>stop_at\<rbrakk> Expr[uniform {..<q}],
                         sample \<lbrakk>S,G,z\<rbrakk> Expr[distr], 
                         localvars localsC localsQ [instantiateOracles adv [instantiateOracles Count [queryGM]]]])"

  assumes "\<And>P. (instantiateOracles Count [P]) = (block [P, assign \<lbrakk>count\<rbrakk> (\<lambda>m. getter count m + 1)])"

  assumes "queryGS = (block [measurement \<lbrakk>in_S\<rbrakk> \<lbrakk>X\<rbrakk> (\<lambda>m. binary_measurement (proj_classical_set (getter S m))),
                            ifthenelse (\<lambda>m. getter in_S m = 1) [assign \<lbrakk>Find\<rbrakk> Expr[True]] [],
                            queryG])"
  assumes "queryGM = block [ifthenelse (\<lambda>m. getter count m = getter stop_at m) 
                             [measurement \<lbrakk>guess\<rbrakk> \<lbrakk>X\<rbrakk> Expr[computational_basis]] [], 
                            queryG]"
  assumes "queryG = (block [qapply \<lbrakk>X,Y\<rbrakk> (\<lambda>m. Uoracle (getter G m))])"

  assumes "distinct_cvars \<lbrakk>count,Find,z,G,S,in_S,stop_at,guess\<rbrakk>"
  assumes "distinct_qvars \<lbrakk>X,Y\<rbrakk>"

(* TODO *)
  (* assumes "disjnt (fvc_oracle_program adv) (set (variable_names \<lbrakk>count,stop_at,guess,Find,G,S,in_S\<rbrakk>))" (* adv can access b,z,X,Y *) *)

  assumes "probability (\<lambda>m. getter count m \<le> q) game_left rho = 1"
  assumes "probability (\<lambda>m. getter count m \<le> q) game_right rho = 1"

  defines "Pleft == probability (\<lambda>m. getter Find m) game_left rho"
  defines "Pright == probability (\<lambda>m. getter guess m \<in> getter S m) game_right rho"

  shows "Pleft \<le> 4 * real q * Pright"
    by (cheat Semi_Classical_Search)

lemmas semi_classical_search' = semi_classical_search[where localsC="Classical_Extra.empty_var" and localsQ="Quantum_Extra2.empty_var", 
    unfolded localvars_empty[of "[_]", unfolded singleton_block]]

ML \<open>
structure Semi_Classical_Search = struct

fun semi_classical_search_tac' scs_rule ctxt = 
  let val pb_tac = O2H.program_body_tac "semiclassical search" ctxt
      val resolve_scs = Misc.succeed_or_error_tac' (resolve_tac ctxt [scs_rule]) ctxt
         (K "Goal should be exactly of the form 'Pr[Find:left(rho)] \<le> 4 * real q * Pr[guess\<in>S:right(rho)]'")
   in
    resolve_scs
    THEN' pb_tac
    THEN' pb_tac
    THEN' pb_tac
    THEN' pb_tac
    THEN' pb_tac
    THEN' pb_tac
    THEN' O2H.distinct_vars_tac ctxt
    THEN' O2H.distinct_vars_tac ctxt
    (* THEN' O2H.free_vars_tac ctxt *)
  end

fun semi_classical_search_tac ctxt i = Misc.fail_tac_on_LAZY_ERROR (DETERM (semi_classical_search_tac' @{thm semi_classical_search'} ctxt i))
                     ORELSE semi_classical_search_tac' @{thm semi_classical_search} ctxt i

end
\<close>

experiment 
  fixes b :: \<open>bit cvariable\<close>
    and Find :: \<open>bool cvariable\<close>
    and S :: \<open>sometype set cvariable\<close>
    and G :: \<open>sometype \<Rightarrow> bit qvariable\<close>
    and z :: \<open>nat list cvariable\<close>
    and count :: \<open>nat cvariable\<close>
    and X :: \<open>sometype qvariable\<close>
    and Y :: \<open>bit qvariable\<close>
    and in_S :: \<open>bit cvariable\<close>
    and guess :: \<open>sometype cvariable\<close>
    and stop_at :: \<open>nat cvariable\<close>
begin

definition test_distr :: "(string set * (string\<Rightarrow>bit) * nat list) distr" where "test_distr = undefined"
definition adv :: oracle_program where "adv = undefined"
definition Count :: oracle_program where "Count = undefined"

definition [program_bodies]: "queryG = (block [qapply \<lbrakk>X,Y\<rbrakk> Expr[Uoracle G]])"
definition [program_bodies]: "queryGS =  (block [measurement \<lbrakk>var_in_S\<rbrakk> \<lbrakk>X\<rbrakk> Expr[binary_measurement (proj_classical_set S)],
                            ifthenelse Expr[in_S=1] [assign \<lbrakk>var_Find\<rbrakk> Expr[True]] [],
                            queryG])"
definition [program_bodies]: "queryGM = block [
  ifthenelse Expr[count=stop_at] [measurement \<lbrakk>var_guess\<rbrakk> \<lbrakk>X\<rbrakk> Expr[computational_basis]] [], queryG]"

(*   assumes "game_right = (block [assign \<lbrakk>count\<rbrakk> Expr[0], sample \<lbrakk>stop_at\<rbrakk> Expr[uniform {..<q}],
                         sample \<lbrakk>S,G,z\<rbrakk> Expr[distr], 
                         instantiateOracles adv [instantiateOracles Count [queryGM]]])"
 *)

definition "q = (123::nat)"

definition [program_bodies]: "left = block [assign \<lbrakk>var_count\<rbrakk> Expr[0], 
        sample \<lbrakk>var_S, var_G, var_z\<rbrakk> Expr[test_distr],
        assign \<lbrakk>var_Find\<rbrakk> (const_expression False),
        localvars \<lbrakk>\<rbrakk> \<lbrakk>\<rbrakk> [instantiateOracles adv [instantiateOracles Count [queryGS]]]]"
definition [program_bodies]: "right = block [assign \<lbrakk>var_count\<rbrakk> Expr[0], 
        sample \<lbrakk>var_stop_at\<rbrakk> Expr[uniform {..<q}],
        sample \<lbrakk>var_S, var_G, var_z\<rbrakk> Expr[test_distr],
        localvars \<lbrakk>\<rbrakk> \<lbrakk>\<rbrakk> [instantiateOracles adv [instantiateOracles Count [queryGM]]]]"

lemma [program_bodies]: "instantiateOracles Count [P] = block [P, assign \<lbrakk>var_count\<rbrakk> Expr[count+1]]" for P  
  by (cheat Count)

lemma fv_adv[program_fv]: "fvc_oracle_program adv = set (variable_names \<lbrakk>X,Y,var_b,var_z\<rbrakk>)" by (cheat fv_adv)

lemma "Pr[Find:left(rho)] \<le> 4 * real q * Pr[guess\<in>S:right(rho)]"
  apply (tactic \<open>Semi_Classical_Search.semi_classical_search_tac \<^context> 1\<close>)
  oops

end

end

