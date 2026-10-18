(ns validaze.versioning-test
  "Model-based test of event versioning.

  A random history of per-version changes (declare required, declare
  optional, delete) is folded into a reference model of which properties
  each version allows, and the validator must agree with the model at
  every version."
  (:require [validaze.core :as core]
            [clojure.string :as string]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- fold-history
  "Turns a matrix of ops (one row per version) into per-version schema diffs
  and per-version model states. Deletes of absent properties are skipped."
  [names op-matrix]
  (loop [rows op-matrix, version 1, state {}, diffs {}, states {}]
    (if (empty? rows)
      {:diffs diffs :states states}
      (let [[diff state'] (reduce (fn [[diff state] [nm op]]
                                    (case op
                                      :skip [diff state]
                                      :required [(assoc diff nm {:required? true}) (assoc state nm :required)]
                                      :optional [(assoc diff nm {:required? false}) (assoc state nm :optional)]
                                      :delete (if (contains? state nm)
                                                [(assoc diff nm nil) (dissoc state nm)]
                                                [diff state])))
                                  [{} state]
                                  (map vector names (first rows)))]
        (recur (rest rows) (inc version) state'
               (assoc diffs version diff) (assoc states version state'))))))

(def ^:private model-gen
  (gen/such-that
   ;; validaze rejects schemas that declare no properties at all
   (fn [{:keys [diffs]}] (some seq (vals diffs)))
   (gen/let [names (gen/vector-distinct (gen/fmap #(str "p_" (string/lower-case %)) gen/string-alphanumeric)
                                        {:min-elements 1 :max-elements 6})
             versions (gen/choose 1 5)
             ops (gen/vector (gen/vector (gen/elements [:skip :skip :required :optional :delete])
                                         (count names))
                             versions)]
     (assoc (fold-history names ops) :names names))
   100))

(defn- event [version props]
  {"event_type" "e" "event_version" version "properties" (zipmap props (repeat "v"))})

(defspec validator-agrees-with-versioning-model 100
  (prop/for-all [{:keys [diffs states]} model-gen]
    (let [referenced (set (mapcat keys (vals diffs)))
          validate (core/validator {"e" diffs} [(zipmap referenced (repeat :string))])]
      (every?
       (fn [[version state]]
         (let [required (for [[p r] state :when (= r :required)] p)
               allowed (keys state)
               absent (remove (set allowed) referenced)]
           (and (nil? (validate (event version required)))
                (nil? (validate (event version allowed)))
                (every? #(= [(format "Missing required keys: [\"%s\"]." %)]
                            (validate (event version (remove #{%} required))))
                        required)
                (every? #(= [(format "Unexpected keys: [\"%s\"]." %)]
                            (validate (event version (cons % required))))
                        absent))))
       states))))
