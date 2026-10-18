# validaze guide

This guide builds up a tracking plan for an online store, one feature at a time. The finished plan is [`examples/validaze/examples/storefront.clj`](../examples/validaze/examples/storefront.clj). Every code block here is executed by the test suite, and each `;; =>` line shows the exact result.

- [Events](#events)
- [Event schemas and versions](#event-schemas-and-versions)
- [Types](#types)
- [Refinements](#refinements)
- [Property lists](#property-lists)
- [Conditional requirements](#conditional-requirements)
- [Super-properties](#super-properties)
- [Validating](#validating)
- [Schema errors](#schema-errors)
- [Introspection](#introspection)
- [Limitations](#limitations)

## Events

validaze validates events shaped like this:

```clojure-skip
{"event_type"               "product_viewed"  ; required string
 "event_version"            2                 ; required integer
 "super_properties_version" 1                 ; optional integer, default 1
 "properties"               {"sku" "TEE-1042" ; required object
                             ...}}
```

Keys are strings, as they come from parsed JSON. `properties` holds the event's own properties and its [super-properties](#super-properties) together.

A validator is built from up to five schemas:

```clojure-skip
(validaze/validator events-schema properties-schema)
(validaze/validator events-schema properties-schema super-properties-schema)
(validaze/validator events-schema properties-schema super-properties-schema refinements)
(validaze/validator events-schema properties-schema super-properties-schema property-lists refinements)
```

The events schema says which properties each version of each event has, and whether they are required. The properties schema gives each property its type. Keeping the two apart means a property such as `currency` has one type everywhere it's used.

## Event schemas and versions

The events schema maps event type → version → property → attributes. Event types and property names are `snake_case`: lowercase letters, digits and underscores.

```clojure
(require '[validaze.core :as validaze])

(def validate
  (validaze/validator
   {"order_completed"
    {1 {"order_id" {:required? true}
        "total"    {:required? true}
        "currency" {:required? true}}
     2 {"total"       nil
        "total_cents" {:required? true}}}}
   [{"order_id"    :string
     "total"       :number
     "total_cents" :nonnegative-integer
     "currency"    :string}]))
```

Versions are numbered from 1 with no gaps. Each version lists only what changed since the one before. Version 2 above keeps `order_id` and `currency`, deletes `total` (`nil` deletes a property), and adds `total_cents`. This is the usual migration when the first version shipped money as float dollars.

Events say which version they follow, so clients that haven't upgraded keep validating:

```clojure
(validate {"event_type" "order_completed" "event_version" 1
           "properties" {"order_id" "o-1" "total" 57.6 "currency" "USD"}})
;; => nil

(validate {"event_type" "order_completed" "event_version" 2
           "properties" {"order_id" "o-1" "total_cents" 5760 "currency" "USD"}})
;; => nil

(validate {"event_type" "order_completed" "event_version" 2
           "properties" {"order_id" "o-1" "total" 57.6 "currency" "USD"}})
;; => ["Missing required keys: [\"total_cents\"]. Unexpected keys: [\"total\"]."]
```

A later version can declare a property again, with new attributes, or after deleting it.

## Types

| Type | Accepts |
| --- | --- |
| `:string` `:number` `:integer` `:boolean` `:object` | the JSON value of that kind (`:integer` values are also `:number`s) |
| `:nonnegative-integer` `:positive-integer` | built-in refinements of `:integer` |
| `:datetime` | built-in refinement of `:string`: ISO 8601 with milliseconds and offset, e.g. `"2026-10-18T14:03:00.000Z"` |
| `[:enum "a" "b" ...]` | one of the listed strings |
| `[:list t]` | a vector whose elements all have type `t` (`t` is a keyword) |
| `[:object k v]` | an object whose keys have type `k` and values have type `v` (`v` is a keyword or a `[:list t]`) |
| any keyword in your [refinements](#refinements) | values passing that refinement |

```clojure
(def validate
  (validaze/validator
   {"products_searched" {1 {"query"        {:required? true}
                            "result_count" {:required? true}
                            "sort"         {:required? false}
                            "skus"         {:required? false}
                            "filters"      {:required? false}}}}
   [{"query"        :string
     "result_count" :nonnegative-integer
     "sort"         [:enum "relevance" "price_asc" "price_desc"]
     "skus"         [:list :string]
     "filters"      [:object :string [:list :string]]}]))

(validate {"event_type" "products_searched" "event_version" 1
           "properties" {"query" "linen shirt" "result_count" 18 "sort" "price_asc"
                         "skus" ["SHI-201" "SHI-202"]
                         "filters" {"size" ["m" "l"] "color" ["white"]}}})
;; => nil

(validate {"event_type" "products_searched" "event_version" 1
           "properties" {"query" "linen shirt" "result_count" 18 "sort" "cheapest"}})
;; => ["'sort' must be one of: #{\"price_desc\" \"relevance\" \"price_asc\"}"]

(validate {"event_type" "products_searched" "event_version" 1
           "properties" {"query" "linen shirt" "result_count" 18 "skus" "SHI-201"}})
;; => ["'skus' must be a vector"]

(validate {"event_type" "products_searched" "event_version" 1
           "properties" {"query" "linen shirt" "result_count" 18 "filters" {"size" "m"}}})
;; => ["'filters' must be an object from type 'string' to type '[ string ]'"]
```

`null` isn't accepted for any type. To leave a property out, omit it.

## Refinements

A refinement is a named type: a parent type, a predicate, and a function that builds the error message. Pass them to `validator` as the last argument:

```clojure
(defn matches [re]
  (fn [v] (and (string? v) (boolean (re-matches re v)))))

(def refinements
  {:sku         [:string [(matches #"[A-Z]{2,4}-\d{3,6}")
                          (fn [v] (str "must be a SKU like \"TEE-1042\", got " (pr-str v)))]]
   :price-cents [:nonnegative-integer [(fn [v] (and (integer? v) (<= v 10000000)))
                                       (fn [_] "must be at most 10000000 cents")]]
   :currency    #{"USD" "EUR" "GBP" "CAD"}})
```

- `[parent [predicate message-fn]]` refines `parent`, which is any type keyword: a base type, a built-in, or another of your refinements. A value must pass every predicate up the chain, and the first failure, starting from the base type, is the one reported. So `:price-cents` reports `-5` as "must be a nonnegative integer" and `20000000` as "must be at most 10000000 cents".
- A set is shorthand for a `:string` refinement that accepts exactly those strings.

> **Predicates must accept any value and return a boolean.** When the validator is built, validaze checks each predicate against its spec by calling it with generated JSON values: strings, numbers, maps, vectors, `nil`. A predicate that throws (say, `re-matches` on a number) or returns a truthy non-boolean (say, the match itself) makes `validator` throw. Guard the type and wrap in `boolean`, as `matches` does above.

```clojure
(validaze/validator {"e" {1 {"sku" {:required? true}}}} [{"sku" :sku}] {}
                    {:sku [:string [#(re-matches #"[A-Z]+-\d+" %) (fn [_] "must be a SKU")]]})
;; throws=> "did not conform to spec"
```

Using them:

```clojure
(def validate
  (validaze/validator
   {"checkout_started" {1 {"skus"           {:required? true}
                           "subtotal_cents" {:required? true}
                           "currency"       {:required? true}}}}
   [{"skus" [:list :sku] "subtotal_cents" :price-cents "currency" :currency}]
   {}
   refinements))

(validate {"event_type" "checkout_started" "event_version" 1
           "properties" {"skus" ["TEE-1042"] "subtotal_cents" 3200 "currency" "USD"}})
;; => nil

(validate {"event_type" "checkout_started" "event_version" 1
           "properties" {"skus" ["TEE-1042" "tee-1042"] "subtotal_cents" -5 "currency" "JPY"}})
;; => ["'skus' must be a vector of type 'sku' and 'sku' must be a SKU like \"TEE-1042\", got \"tee-1042\""
;;     "'subtotal_cents' must be a nonnegative integer"
;;     "'currency' must be one of: #{\"EUR\" \"GBP\" \"CAD\" \"USD\"}"]
```

## Property lists

When several events share a group of properties, name the group once and include it with `:includes`. Property lists come before refinements in the five-argument form:

```clojure
(def product-props
  [{"sku" :sku "product_name" :string "price_cents" :price-cents "currency" :currency
    "cart_id" :string "quantity" :positive-integer}])

(def property-lists
  {:product {"sku"          {:required? true}
             "product_name" {:required? true}
             "price_cents"  {:required? true}
             "currency"     {:required? true}}})

(def validate
  (validaze/validator
   {"product_viewed"        {1 {:includes [:product]}}
    "product_added_to_cart" {1 {:includes [:product]
                                "cart_id"  {:required? true}
                                "quantity" {:required? true}}}}
   product-props
   {}
   property-lists
   refinements))

(validate {"event_type" "product_added_to_cart" "event_version" 1
           "properties" {"sku" "TEE-1042" "product_name" "Heavyweight Tee" "price_cents" 3200
                         "currency" "USD" "cart_id" "c-9" "quantity" 2}})
;; => nil

(validate {"event_type" "product_viewed" "event_version" 1
           "properties" {"sku" "TEE-1042" "price_cents" 3200 "currency" "USD"}})
;; => ["Missing required keys: [\"product_name\"]."]
```

A property list only sets requiredness. Types still come from the properties schema.

## Conditional requirements

Instead of `true` or `false`, `:required?` can be a condition:

- `[:when "other" :exists]`: required whenever `other` is present.
- `[:when "other" #{"a" "b"}]`: required whenever `other` is one of those values.

```clojure
(def validate
  (validaze/validator
   {"order_completed"
    {1 {"total_cents"      {:required? true}
        "coupon_code"      {:required? false}
        "discount_cents"   {:required? [:when "coupon_code" :exists]}
        "fulfillment"      {:required? true}
        "shipping_country" {:required? [:when "fulfillment" #{"delivery"}]}}
     2 {"payment_method" {:required? false}}}}
   [{"total_cents" :nonnegative-integer "coupon_code" :string "discount_cents" :nonnegative-integer
     "fulfillment" [:enum "delivery" "pickup"] "shipping_country" :string
     "payment_method" [:enum "card" "paypal"]}]))

(validate {"event_type" "order_completed" "event_version" 1
           "properties" {"total_cents" 5760 "fulfillment" "pickup"}})
;; => nil

(validate {"event_type" "order_completed" "event_version" 1
           "properties" {"total_cents" 5760 "fulfillment" "delivery" "coupon_code" "FALL10"}})
;; => ["'discount_cents' is required when 'coupon_code' exists"
;;     "'shipping_country' is required when 'fulfillment' is any of: #{\"delivery\"}"]
```

Conditions carry forward to later versions like any other attribute:

```clojure
(validate {"event_type" "order_completed" "event_version" 2
           "properties" {"total_cents" 5760 "fulfillment" "delivery"}})
;; => ["'shipping_country' is required when 'fulfillment' is any of: #{\"delivery\"}"]
```

## Super-properties

Super-properties, as in Mixpanel, are properties a client registers once and then sends with every event: an anonymous id, the platform, the app version. They're declared once, with their type and requiredness together, and are versioned separately from events:

```clojure
(def super-properties
  {1 {"anonymous_id" {:type :string :required? true}
      "platform"     {:type [:enum "web" "ios" "android"] :required? true}}
   2 {"user_id"      {:type :string :required? false}
      "app_version"  {:type :string :required? [:when "platform" #{"ios" "android"}]}}})

(def validate
  (validaze/validator {"app_opened" {1 {"cold_start" {:required? true}}}}
                      [{"cold_start" :boolean}]
                      super-properties))
```

Super-property versions are **additive**: each version may only add properties, and version *n* includes everything in versions 1 to *n*. An event picks one with `"super_properties_version"`, which defaults to 1. The super-properties sit in `properties` next to the event's own:

```clojure
(validate {"event_type" "app_opened" "event_version" 1
           "properties" {"anonymous_id" "a-1" "platform" "web" "cold_start" true}})
;; => nil

(validate {"event_type" "app_opened" "event_version" 1 "super_properties_version" 2
           "properties" {"anonymous_id" "a-1" "platform" "ios" "cold_start" true}})
;; => ["'app_version' is required when 'platform' is any of: #{\"ios\" \"android\"}"]

(validate {"event_type" "app_opened" "event_version" 1
           "properties" {"anonymous_id" "a-1" "platform" "ios" "cold_start" true "user_id" "u-1"}})
;; => ["Unexpected keys: [\"user_id\"]."]
```

A super-property can't also appear in the properties schema. Every event gets it automatically.

## Validating

`validate` takes one event or a sequence of events:

- **One event:** `nil` if valid, otherwise a vector of messages.
- **A sequence:** `nil` if every event is valid, otherwise a map from the index of each invalid event to its messages.

```clojure
(require '[validaze.examples.storefront :as storefront])

(storefront/validate
 [(:product-viewed storefront/valid-events)
  {"event_type" "product_viewed" "event_version" 3 "properties" {}}
  (:products-searched storefront/valid-events)
  {"event_type" "product_viewed"}])
;; => {1 ["There is no version 3 for event 'product_viewed'"]
;;     3 ["Missing required keys: [\"properties\" \"event_version\"]."]}
```

Messages come back in no particular order. Checks run in three stages, and each stage runs only if the one before it passed:

1. **Envelope.** The top-level keys and their types.
2. **Keys.** The event type and version (and super-properties version) exist, all required properties are present, and nothing undeclared is.
3. **Values.** Every property's type, and every conditional requirement.

A single message can report problems from several properties at once. The messages:

| Message | Meaning |
| --- | --- |
| `Missing required keys: [...].` | required properties (or envelope keys) absent |
| `Unexpected keys: [...].` | properties not declared for this version |
| `'p' must be ...` | `p`'s value doesn't have its type; the rest of the message comes from the failing type or refinement |
| `'p' is required when 'q' exists` | a `[:when "q" :exists]` requirement |
| `'p' is required when 'q' is any of: #{...}` | a `[:when "q" #{...}]` requirement |
| `There is no version n for event 't'` | unknown version, or unknown event type |
| `There is no super-properties version n` | unknown super-properties version |

## Schema errors

`validator` checks the schemas themselves and throws if they're inconsistent, so a broken plan fails at startup:

```clojure
;; a property used in an event but given no type
(validaze/validator {"e" {1 {"sku" {:required? true}}}} [{"price" :integer}])
;; throws=> "undefined referenced properties"

;; a typed property that no event uses
(validaze/validator {"e" {1 {"sku" {:required? true}}}} [{"sku" :string "price" :integer}])
;; throws=> "unreferenced defined properties"

;; deleting a property that was never declared
(validaze/validator {"e" {1 {"sku" {:required? true}} 2 {"price" nil}}} [{"sku" :string "price" :integer}])
;; throws=> "Attempt to delete non-existent property 'price' on event 'e' in version 2"

;; a gap between versions
(validaze/validator {"e" {1 {"sku" {:required? true}} 3 {"sku" {:required? false}}}} [{"sku" :string}])
;; throws=> "one-based-contig-range?"

;; a type keyword that is neither built in nor in your refinements
(validaze/validator {"e" {1 {"sku" {:required? true}}}} [{"sku" :skuu}])
;; throws=> "Unknown refinement: :skuu"

;; an :includes of a property list that doesn't exist
(validaze/validator {"e" {1 {:includes [:product] "sku" {:required? true}}}} [{"sku" :string}] {} {} {})
;; throws=> "Undefined property list references: [:product]"

;; a super-property that is also an event property
(validaze/validator {"e" {1 {"platform" {:required? true}}}} [{"platform" :string}]
                    {1 {"platform" {:type :string :required? true}}})
;; throws=> "Super-properties cannot be explicitly referenced"

;; a super-properties version that redeclares an earlier property
(validaze/validator {"e" {1 {"sku" {:required? true}}}} [{"sku" :string}]
                    {1 {"platform" {:type :string :required? true}}
                     2 {"platform" {:type :string :required? false}}})
;; throws=> "duplicates existing properties"
```

Shape errors, such as a property name that isn't `snake_case`, come from `clojure.spec` as an `ExceptionInfo` explaining the offending part.

## Introspection

The validator's metadata has `:event->schema`, which gives the resolved schema for one version of one event: every key with its requiredness, and every type. It takes the super-properties version (`nil` for the default), the event type and the event version:

```clojure
((:event->schema (meta storefront/validate)) nil "order_completed" 2)
;; => {:keys {"coupon_code"      {:required? false}
;;            "currency"         {:required? true}
;;            "discount_cents"   {:required? [:when "coupon_code" :exists]}
;;            "fulfillment"      {:required? true}
;;            "order_id"         {:required? true}
;;            "shipping_country" {:required? [:when "fulfillment" #{"delivery"}]}
;;            "skus"             {:required? true}
;;            "total_cents"      {:required? true}}
;;     :super-keys {"anonymous_id" {:required? true}
;;                  "platform"     {:required? true}}
;;     :types {"anonymous_id"     :uuid
;;             "coupon_code"      :string
;;             "currency"         :currency
;;             "discount_cents"   :price-cents
;;             "fulfillment"      [:enum "delivery" "pickup"]
;;             "order_id"         :uuid
;;             "platform"         [:enum "web" "ios" "android"]
;;             "shipping_country" :country-code
;;             "skus"             [:list :sku]
;;             "total_cents"      :price-cents}}
```

It returns `nil` for an unknown event, version or super-properties version. This is handy for generating documentation, or test data: the test suite's `validaze.event-gen` builds generators of valid events from it.

`refinements->validators` turns refinements into standalone value validators, including the built-ins:

```clojure
(def validators (validaze/refinements->validators refinements))

((validators :sku) "TEE-1042")
;; => nil

((validators :sku) "tee")
;; => "must be a SKU like \"TEE-1042\", got \"tee\""

((validators :price-cents) 20000000)
;; => "must be at most 10000000 cents"

((validators :datetime) "2026-10-18T14:03:00.000Z")
;; => nil
```

## Limitations

- Building a validator is relatively slow: about 50 ms for the storefront plan. `validator` is instrumented with `clojure.spec`, and checking the refinement predicates calls each one many times. Build validators once at startup. Validating a storefront event takes about a millisecond.
- `[:object ...]` errors name the property but not the offending entry.
- An unknown event type reports `There is no version n for event 't'`.
- `validate` expects a map or a sequence of maps. Other input throws.
- If you instrument every spec'd function (`(stest/instrument)`, common in development), validaze's internal specs run too. Some schema mistakes, such as an unknown type keyword, are then reported as spec explanations instead of the messages shown in [Schema errors](#schema-errors).
