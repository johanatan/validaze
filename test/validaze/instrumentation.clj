(ns validaze.instrumentation
  "Scoped clojure.spec instrumentation for tests, so instrumenting
  everything in one test namespace doesn't change how later ones run."
  (:require [clojure.spec.test.alpha :as stest]))

(defn with-full-instrumentation
  "Runs f with every spec'd fn instrumented, then restores exactly the
  previous instrumentation (validaze.core instruments `validator` and
  `refinements->validators` at load). clojure.spec doesn't expose what is
  instrumented, so this reads its private registry."
  [f]
  (let [before (set (keys @@#'stest/instrumented-vars))
        syms (stest/instrument (stest/instrumentable-syms))]
    (try (f)
         (finally (stest/unstrument (remove #(before (resolve %)) syms))))))
