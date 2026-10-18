(ns validaze.storefront-test
  "Tests of the storefront example tracking plan.

  The sample events are checked by example. Everything else is property
  based: events are generated from the validator's own schema, then
  corrupted in one specific way, and the validator must report exactly that."
  (:require [validaze.examples.storefront :as storefront]
            [validaze.event-gen :as eg]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private hex (gen/elements (seq "0123456789abcdef")))
(defn- hex-string [n] (gen/fmap #(apply str %) (gen/vector hex n)))
(defn- upper [lo hi] (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "ABCDEFGHIJKLMNOPQRSTUVWXYZ")) lo hi)))
(defn- digits [lo hi] (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "0123456789")) lo hi)))

;; Generators for the predicate refinements. Set refinements (:currency)
;; and built-ins are handled by validaze.event-gen.
(def refinement-gens
  {:uuid         (gen/fmap #(string/join "-" %)
                           (gen/tuple (hex-string 8) (hex-string 4) (hex-string 4) (hex-string 4) (hex-string 12)))
   :sku          (gen/fmap (fn [[a d]] (str a "-" d)) (gen/tuple (upper 2 4) (digits 3 6)))
   :country-code (upper 2 2)
   :semver       (gen/fmap #(string/join "." %) (gen/tuple gen/nat gen/nat gen/nat))
   :locale       (gen/elements ["en" "en-US" "fr-CA" "de" "pt-BR"])
   :search-query (gen/such-that #(<= 1 (count (string/trim %)) 200) gen/string-alphanumeric 100)
   :price-cents  (gen/choose 0 10000000)
   :quantity     (gen/choose 1 99)})

(def ^:private validate storefront/validate)
(def ^:private event-gen (eg/event-gen validate refinement-gens))
(def ^:private refinements (:refinements (meta validate)))

(defn- schema [event] (eg/schema-for validate event))
(defn- all-keys [event] (let [s (schema event)] (merge (:keys s) (:super-keys s))))

;; Examples

(deftest sample-valid-events-pass
  (doseq [[k event] storefront/valid-events]
    (is (nil? (validate event)) (str k))))

(deftest sample-invalid-events-fail-as-documented
  (doseq [[k [event expected]] storefront/invalid-events]
    (is (= expected (set (validate event))) (str k))))

;; Properties

(defspec generated-events-are-valid 200
  (prop/for-all [event event-gen]
    (nil? (validate event))))

(defspec removing-a-required-property-reports-it-missing 100
  (prop/for-all [[event k] (gen/bind event-gen
                                     (fn [e]
                                       (let [required (for [[k {r :required?}] (all-keys e) :when (true? r)] k)]
                                         (gen/tuple (gen/return e) (gen/elements required)))))]
    (= [(format "Missing required keys: [\"%s\"]." k)]
       (validate (update event "properties" dissoc k)))))

(defspec adding-an-undeclared-property-reports-it-unexpected 100
  (prop/for-all [event event-gen
                 suffix (gen/fmap string/lower-case gen/string-alphanumeric)]
    (let [k (str "undeclared_" suffix)]
      (= [(format "Unexpected keys: [\"%s\"]." k)]
         (validate (assoc-in event ["properties" k] "x"))))))

(defspec a-value-of-the-wrong-kind-reports-exactly-that-property 200
  (prop/for-all [[event k bad] (gen/bind event-gen
                                         (fn [e]
                                           (gen/let [k (gen/elements (sort (keys (e "properties"))))
                                                     bad (gen/elements (eg/wrong-kind-values
                                                                        (eg/base-kind refinements (get-in (schema e) [:types k]))))]
                                             [e k bad])))]
    (let [errors (validate (assoc-in event ["properties" k] bad))]
      (and (= 1 (count errors))
           (string/starts-with? (first errors) (format "'%s' must be" k))))))

(defn- triggered-conditionals
  "Conditionally required properties present in the event whose condition holds."
  [event]
  (let [props (event "properties")]
    (for [[k {r :required?}] (all-keys event)
          :when (and (vector? r) (contains? props k) (eg/condition-holds? r props))]
      [k r])))

(defspec removing-a-triggered-conditional-property-reports-the-condition 100
  (prop/for-all [[event k [_ other _]]
                 (gen/bind (gen/such-that (comp seq triggered-conditionals) event-gen 200)
                           (fn [e] (gen/fmap (partial cons e) (gen/elements (triggered-conditionals e)))))]
    (let [errors (validate (update event "properties" dissoc k))]
      (and (= 1 (count errors))
           (string/starts-with? (first errors) (format "'%s' is required when '%s' " k other))))))

;; Batches report errors by index, and each entry is what validating that
;; event alone returns.
(defspec batch-errors-are-indexed-single-event-errors 50
  (prop/for-all [events (gen/vector (gen/one-of [event-gen
                                                 ;; envelope errors
                                                 (gen/fmap #(dissoc % "properties") event-gen)
                                                 (gen/fmap #(assoc % "event_version" "1") event-gen)
                                                 ;; key errors
                                                 (gen/fmap #(assoc % "event_version" 99) event-gen)
                                                 (gen/fmap #(update % "properties" dissoc "anonymous_id") event-gen)])
                                    0 8)]
    (let [expected (into {} (keep-indexed (fn [i e] (when-let [r (validate e)] [i r])) events))]
      (= (not-empty expected) (validate events)))))
