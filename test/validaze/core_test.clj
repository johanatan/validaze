(ns validaze.core-test
  (:require [validaze.core :as core]
            [clojure.test :refer :all]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as gen]))

(defn instrument-all-syms []
  (stest/instrument (stest/instrumentable-syms)))

(defn spec-check-is-success [check-res]
  (stest/summarize-results check-res)
  (if (nil? check-res)
    (is false "stest/check result was nil. did you pass it any valid symbols?")
    (let [check #(let [res %1]
                   (is (= true (-> res :clojure.spec.test.check/ret :result))
                       (format "spec check failure:\r\n%s" (with-out-str (clojure.pprint/pprint res)))))]
      (doall (map check check-res)))))

(defn explain-valid? [spec data]
  (is (s/valid? spec data)
      (format "`valid?` failure explanation:\r\n%s" (s/explain-str spec data))))

(deftest ^:eftest/slow specd-functions-pass-check
  (instrument-all-syms)
  (let [fns [`core/enum-validator
             `core/-list-validator
             `core/-refinement-kwd->validator
             `core/transform-msg
             `core/prepend-prop
             `core/-prop-spec->prop-validator
             `core/validate-to-msg
             `core/keys-validator]]
    (spec-check-is-success (stest/check fns))))

(deftest generator-spec-congruence
  (instrument-all-syms)
  ;; These are the specs in our project defined with `with-gen`.
  ;; Verifies that generator and spec are congruent with one another.
  (doseq [spec '(::core/json-map
                 ::core/refinements
                 ::core/refinements-with-string
                 ::core/refinements-with-string-and-object
                 ::core/refinements-refinement-kwd-tup
                 ::core/snake-cased-alpha-numeric
                 ::core/events-schema
                 ::core/refinements-property-refinement-tup
                 ::core/value-level-validation-fn
                 ::core/primitive-message-fn
                 ::core/validation-fn
                 ::core/message-fn
                 ::core/enum-refinement
                 ::core/list-refinement
                 ::core/object-refinement
                 ::core/primitive-validator
                 ::core/validator)]
    (is (gen/sample (s/gen spec) 1000)
        (format "incongruence between spec and its generator: %s" spec))))

(deftest super-props-behave-when-unspecified
  (let [events-schema {"event1" {1 {"prop1" {:required? true}}}}
        props-schema [{"prop1" :string}]
        super-props-schema {1 {"super_prop1" {:type :string :required? true}}}
        validator-with-super-props (core/validator events-schema props-schema super-props-schema)
        validator-without-super-props (core/validator events-schema props-schema)
        event {"event_type" "event1" "event_version" 1 "properties" {"prop1" "blah"}}]
    (is (= ["Missing required keys: [\"super_prop1\"]."]
           (validator-with-super-props event)))
    (is (nil? (validator-without-super-props event)))))

(deftest when-prop-works
  (let [exists-schema {"event1" {1 {"prop1" {:required? false}
                                    "prop2" {:required? [:when "prop1" :exists]}}}}
        is-one-of-schema (assoc-in exists-schema ["event1" 1 "prop2" :required?] [:when "prop1" #{"foo" "bar"}])
        props-schema [{"prop1" :string "prop2" :string}]
        exists-validator (core/validator exists-schema props-schema)
        is-one-of-validator (core/validator is-one-of-schema props-schema)
        base-event {"event_type" "event1" "event_version" 1}]
    ; exists tests
    (is (= ["'prop2' is required when 'prop1' exists"]
           (exists-validator (merge base-event {"properties" {"prop1" "p1"}}))))
    (is (nil? (exists-validator (merge base-event {"properties" {"prop1" "p1" "prop2" "p2"}}))))
    (is (nil? (exists-validator (merge base-event {"properties" {}}))))
    ; is-one-of tests
    (is (= ["'prop2' is required when 'prop1' is any of: #{\"foo\" \"bar\"}"]
           (is-one-of-validator (merge base-event {"properties" {"prop1" "foo"}}))))
    (is (nil? (is-one-of-validator (merge base-event {"properties" {"prop1" "bar" "prop2" "p2"}}))))
    (is (nil? (is-one-of-validator (merge base-event {"properties" {}}))))))

(deftest errors-returned-as-vector-or-map
  (let [events-schema {"event1" {1 {"prop1" {:required? true}}}}
        props-schema [{"prop1" :string}]
        validator (core/validator events-schema props-schema)
        event {"event_type" "event1" "event_version" 1 "properties" {}}
        num-events 10
        multi-res (validator (repeat num-events event))]
    (is (vector? (validator event)))
    (is (map? multi-res))
    (is (= (sort (keys multi-res)) (range num-events)))))
