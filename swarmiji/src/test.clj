(ns server)

(use 'org.runa.swarmiji.sevak.sevak-core)

(defsevak add-numbers [a b]
  (+ a b))

(defsevak sub-numbers [a b]
  (- a b))

(boot-sevak-server)

