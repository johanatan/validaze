(ns validaze.regression-test
  "Properties guarding fixes to schema checks, conditional requires and
  `refinements->validators`."
  (:require [validaze.core :as core]
            [validaze.refinements :as refinements]
            [validaze.instrumentation :refer [with-full-instrumentation]]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private name-gen
  (gen/fmap #(apply str %) (gen/vector gen/char-alpha-numeric 1 8)))

(def ^:private prop-name-gen
  (gen/fmap string/lower-case name-gen))

(defn- distinct-names [n]
  (gen/vector-distinct prop-name-gen {:num-elements n}))

(defn- thrown-message [f]
  (try (f) nil (catch IllegalStateException e (.getMessage e))))

(defn- event [type version props & [super-version]]
  (cond-> {"event_type" type "event_version" version "properties" props}
    super-version (assoc "super_properties_version" super-version)))

;; Deleting a property the event never had is a schema error.
(defspec deleting-a-nonexistent-property-is-rejected 50
  (prop/for-all [[declared absent] (gen/fmap (juxt butlast last) (distinct-names 4))]
    (let [events {"e" {1 (zipmap declared (repeat {:required? true}))
                       2 {absent nil}}}
          props [(zipmap (conj (vec declared) absent) (repeat :string))]]
      (some-> (thrown-message #(core/validator events props))
              (string/includes? (format "delete non-existent property '%s'" absent))))))

;; A property name may not be both a super-property and an event property.
(defspec super-properties-and-event-properties-are-disjoint 50
  (prop/for-all [[shared other] (distinct-names 2)
                 super-version (gen/choose 1 3)]
    (let [events {"e" {1 {shared {:required? true} other {:required? true}}}}
          props [{shared :string other :string}]
          super (into {} (for [v (range 1 (inc super-version))]
                           [v (if (= v super-version)
                                {shared {:type :string :required? true}}
                                {(str "s" v) {:type :string :required? false}})]))]
      (some-> (thrown-message #(core/validator events props super))
              (string/includes? "Super-properties cannot be explicitly referenced")))))

;; A `:when` requirement declared in v1 keeps applying in every later version.
(defspec conditional-requires-carry-forward-across-versions 30
  (prop/for-all [extra-props (gen/vector-distinct
                              (gen/fmap #(str "extra_" %) prop-name-gen)
                              {:min-elements 0 :max-elements 5})]
    (let [later (into {} (map-indexed (fn [i p] [(+ 2 i) {p {:required? false}}]) extra-props))
          events {"checkout" (merge {1 {"coupon" {:required? false}
                                        "discount_cents" {:required? [:when "coupon" :exists]}}}
                                    later)}
          props [(merge {"coupon" :string "discount_cents" :nonnegative-integer}
                        (zipmap extra-props (repeat :string)))]
          validator (core/validator events props)]
      (every? (fn [version]
                (and (= ["'discount_cents' is required when 'coupon' exists"]
                        (validator (event "checkout" version {"coupon" "FALL10"})))
                     (nil? (validator (event "checkout" version {"coupon" "FALL10" "discount_cents" 500})))))
              (range 1 (+ 2 (count extra-props)))))))

;; A `:when` super-property applies from the version that declares it onward.
(defspec super-property-conditional-requires-are-enforced 30
  (prop/for-all [declared-at (gen/choose 2 4)
                 extra-versions (gen/choose 0 2)]
    (let [top (+ declared-at extra-versions)
          super (into {1 {"session_id" {:type :string :required? false}}}
                      (for [v (range 2 (inc top))]
                        [v (if (= v declared-at)
                             {"plan" {:type [:enum "free" "pro"] :required? [:when "session_id" :exists]}}
                             {(str "s" v) {:type :string :required? false}})]))
          validator (core/validator {"app_open" {1 {"cold_start" {:required? true}}}}
                                    [{"cold_start" :boolean}]
                                    super)]
      (every? (fn [super-version]
                (let [res (validator (event "app_open" 1 {"cold_start" true "session_id" "s1"} super-version))]
                  (if (>= super-version declared-at)
                    (= ["'plan' is required when 'session_id' exists"] res)
                    (nil? res))))
              (range 1 (inc top))))))

;; `event->schema` reports super-property requiredness as declared, including
;; `:when` conditions, and nil selects the default super-properties version.
(defspec event->schema-reports-super-property-requiredness 30
  (prop/for-all [declared-at (gen/choose 2 4)
                 extra-versions (gen/choose 0 2)]
    (let [top (+ declared-at extra-versions)
          condition [:when "session_id" :exists]
          super (into {1 {"session_id" {:type :string :required? false}}}
                      (for [v (range 2 (inc top))]
                        [v (if (= v declared-at)
                             {"plan" {:type :string :required? condition}}
                             {(str "s" v) {:type :string :required? true}})]))
          schema-of (:event->schema (meta (core/validator {"e" {1 {"a" {:required? true}}}}
                                                          [{"a" :string}] super)))]
      (and (= (schema-of nil "e" 1) (schema-of 1 "e" 1))
           (every? (fn [v]
                     (= (when (>= v declared-at) {:required? condition})
                        (get-in (schema-of v "e" 1) [:super-keys "plan"])))
                   (range 1 (inc top)))))))

;; In a batch, an envelope error in one event must not hide other events'
;; errors: each entry is what validating that event alone returns.
(defspec batch-entries-are-independent-of-other-events 50
  (prop/for-all [events (gen/vector
                         (gen/elements [{"event_type" "e" "event_version" 1 "properties" {"a" "x"}}
                                        {"event_type" "e" "event_version" 1 "properties" {}}
                                        {"event_type" "e" "event_version" 2 "properties" {"a" "x"}}
                                        {"event_type" "e" "event_version" 1}
                                        {"event_type" "e" "event_version" "1" "properties" {"a" "x"}}
                                        {"event_type" "e" "event_version" 1 "properties" {"a" "x"} "extra" 1}])
                         0 8)]
    (let [validate (core/validator {"e" {1 {"a" {:required? true}}}} [{"a" :string}])
          expected (into {} (keep-indexed (fn [i e] (when-let [r (validate e)] [i r])) events))]
      (and (every? vector? (vals expected))
           (= (not-empty expected) (validate events))))))

;; Set refinements become predicates that return booleans, as their spec
;; requires; a bare set returns its element or nil, which fails under
;; instrumentation.
(defspec set-refinement-predicates-return-booleans 50
  (prop/for-all [members (gen/vector-distinct name-gen {:min-elements 1 :max-elements 6})
                 others (gen/vector (gen/one-of [gen/string-alphanumeric gen/large-integer (gen/return nil)]) 1 10)]
    (let [allowed (set members)
          validate (core/validator {"e" {1 {"a" {:required? true}}}} [{"a" :allowed}] {} {:allowed allowed})
          [_ [pred _]] ((:refinements (meta validate)) :allowed)]
      (and (= allowed (:set (meta pred)))
           (every? true? (map pred members))
           (every? #(= (contains? allowed %) (pred %)) others)))))

;; Under full instrumentation, internal predicates and message fns are
;; checked like user ones, so they must accept any value. [:list ...],
;; [:object ...] and set types used to throw there.
(def ^:private any-json
  (gen/one-of [gen/string-alphanumeric gen/large-integer gen/boolean (gen/return nil)
               (gen/vector gen/string-alphanumeric 0 3)
               (gen/map gen/string-alphanumeric gen/large-integer {:max-elements 3})]))

(deftest collection-and-set-types-validate-under-full-instrumentation
  (with-full-instrumentation
    #(let [result (tc/quick-check
                   30
                   (prop/for-all [tags any-json, counts any-json, color any-json]
                     (let [validate (core/validator {"e" {1 {"tags" {:required? true}
                                                             "counts" {:required? true}
                                                             "color" {:required? true}}}}
                                                    [{"tags" [:list :tag] "counts" [:object :string :integer]
                                                      "color" :color}]
                                                    {}
                                                    {:tag #{"new" "sale"} :color #{"red" "blue"}})
                           result (validate {"event_type" "e" "event_version" 1
                                             "properties" {"tags" tags "counts" counts "color" color}})]
                       (or (nil? result) (every? string? result)))))]
       (is (:result result) (pr-str (dissoc result :result-data))))))

;; Refinement chains, modelled directly: each link is a threshold predicate
;; on top of its parent; the validator reports the first failing link from
;; the root, or nil when every link holds.
(def ^:private base-types
  {:integer {:valid? integer? :msg "must be an integer" :value gen/large-integer
             :link (fn [n] [#(and (integer? %) (>= % n)) (format "must be at least %d" n)])}
   :string  {:valid? string? :msg "must be a string" :value gen/string-alphanumeric
             :link (fn [n] [#(and (string? %) (>= (count %) n)) (format "must have length at least %d" n)])}})

(def ^:private chain-gen
  (gen/let [root (gen/elements (keys base-types))
            thresholds (gen/vector (gen/choose -5 8) 1 4)
            names (gen/vector-distinct (gen/fmap #(keyword (str "r_" %)) prop-name-gen)
                                       {:num-elements (count thresholds)})]
    {:root root :thresholds thresholds :names names}))

(defn- chain->refinements [{:keys [root thresholds names]}]
  (let [link (get-in base-types [root :link])]
    (into {} (map (fn [parent nm n]
                    (let [[pred msg] (link n)] [nm [parent [pred (constantly msg)]]]))
                  (cons root names) names thresholds))))

(defn- model-result [{:keys [root thresholds]} depth value]
  (let [{:keys [valid? msg link]} (base-types root)]
    (if-not (valid? value)
      msg
      (some (fn [n] (let [[pred m] (link n)] (when-not (pred value) m)))
            (take depth thresholds)))))

(defspec refinements->validators-matches-chain-model 40
  (prop/for-all [chain chain-gen
                 values (gen/vector (gen/one-of [gen/large-integer gen/string-alphanumeric gen/boolean]) 1 20)]
    (let [validators (core/refinements->validators (chain->refinements chain))]
      (every? (fn [[depth nm]]
                (every? #(= (model-result chain depth %) ((validators nm) %)) values))
              (map-indexed (fn [i nm] [(inc i) nm]) (:names chain))))))

;; Set refinements may appear in `refinements->validators`, alone or as parents.
(defspec refinements->validators-accepts-set-refinements 40
  (prop/for-all [members (gen/vector-distinct name-gen {:min-elements 1 :max-elements 6})
                 values (gen/vector gen/string-alphanumeric 1 20)]
    (let [allowed (set members)
          banned (first members)
          validators (core/refinements->validators
                      {:allowed allowed
                       :allowed-but-first [:allowed [#(and (string? %) (not= % banned)) (constantly "banned")]]})]
      (every? (fn [v]
                (and (= (nil? ((validators :allowed) v)) (contains? allowed v))
                     (= (nil? ((validators :allowed-but-first) v)) (and (contains? allowed v) (not= v banned)))))
              (concat members values)))))

;; Refinement predicates are specced to return booleans; the built-in
;; :datetime predicate must too, or it fails under instrumentation.
(defspec datetime-predicate-is-boolean-and-accepts-iso-instants-only 50
  (prop/for-all [millis (gen/choose 0 4102444800000)
                 junk gen/string-alphanumeric
                 other (gen/one-of [gen/large-integer gen/boolean (gen/return nil)
                                    (gen/vector gen/string-alphanumeric)])]
    (let [[_ [pred _]] (refinements/user-defined-refinements :datetime)
          iso (.format (.withZone (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                                  java.time.ZoneOffset/UTC)
                       (java.time.Instant/ofEpochMilli millis))]
      (and (true? (pred iso))
           (false? (pred (str "x" junk)))
           (false? (pred other))))))
