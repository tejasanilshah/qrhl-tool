theory Tactics
  imports Encoding
begin

lemma seq:
  assumes "c = c1@c2" and "d = d1@d2"
  assumes "qrhl A c1 d1 B"
  and "qrhl B c2 d2 C"
  shows "qrhl A c d C"
  sorry

lemma wp1_assign:
  fixes A B x e
  defines "A \<equiv> subst_expression (substitute1 (index_var True x) (index_expression True e)) B"
  shows "qrhl A [assign x e] [] B"
  sorry

ML_file "tactics.ML"

method_setup seq = {*
  Scan.lift Parse.nat -- Scan.lift Parse.nat -- Scan.lift Parse.term >> (fn ((i,j),B) => fn ctx =>
    SIMPLE_METHOD (Tactics.seq_tac i j (Encoding.read_predicate ctx B) ctx 1))
*}

end