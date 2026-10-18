# validaze

Validate analytics events against a versioned tracking plan, written as plain Clojure data.

```clojure
(require '[validaze.core :as validaze])

(def validate
  (validaze/validator
   ;; events: event type -> version -> property -> requiredness
   {"product_viewed" {1 {"sku"         {:required? true}
                         "price_cents" {:required? true}
                         "category"    {:required? false}}}}
   ;; properties: property -> type
   [{"sku"         :string
     "price_cents" :nonnegative-integer
     "category"    [:enum "apparel" "footwear" "home"]}]))

(validate {"event_type"    "product_viewed"
           "event_version" 1
           "properties"    {"sku" "TEE-1042" "price_cents" 3200}})
;; => nil

(validate {"event_type"    "product_viewed"
           "event_version" 1
           "properties"    {"sku" 1042 "price_cents" -1 "category" "toys"}})
;; => ["'sku' must be a string"
;;     "'price_cents' must be a nonnegative integer"
;;     "'category' must be one of: #{\"footwear\" \"home\" \"apparel\"}"]
```

A valid event returns `nil`. An invalid one returns every problem found, as human-readable messages ready to send back to the team or client that produced the event.

## Why

Tracking plans drift. Clients send properties nobody declared, rename them, change their types, or keep sending them long after they were retired. validaze keeps the plan in one place and checks events against it at ingestion.

The event model follows [Mixpanel](https://docs.mixpanel.com/docs/data-structure/events-and-properties)'s: an event has a type and a map of properties, and **super-properties** are properties registered once and sent with every event, such as a device id or a platform. On top of that model, validaze adds:

- **Versions.** Each event type has numbered versions. A version lists only what changed, and `nil` deletes a property, so old clients keep validating against the version they send while new clients move on.
- **Refinement types.** Build domain types like `:sku`, `:currency` or `:price-cents` by refining a base type with a predicate, and refine those further.
- **Conditional requirements.** `[:when "coupon_code" :exists]` or `[:when "fulfillment" #{"delivery"}]`.
- **Property lists.** Name a group of properties once and include it in many events.
- **Strict keys.** Undeclared properties are errors, not silently accepted.
- **Fail-fast schemas.** Mistakes in the plan itself (undefined or unused properties, gaps between versions, deleting a property that doesn't exist) throw when the validator is built, not when an event arrives.

## Install

Leiningen:

```
[yummly/validaze "1.4.1"]
```

deps.edn:

```
yummly/validaze {:mvn/version "1.4.1"}
```

Fixes made on `master` since 1.4.1 aren't released yet.

## Learn more

- **[Guide](docs/guide.md).** Every feature, walked through on a realistic tracking plan, plus a reference for error messages and schema errors.
- **[Storefront example](examples/validaze/examples/storefront.clj).** The full tracking plan the guide uses: five events, a versioned migration, super-properties and custom refinements.

## Development

```
lein test
```

The tests are mostly property based. `validaze.event-gen` (under `test/`) generates events that a validator accepts, using the validator's own schema. The tests then corrupt them in one specific way and check that the validator reports exactly that. `validaze.versioning-test` checks versioning against an independent model. The documentation's code blocks are executed by `validaze.docs-test`, so the examples here stay true.

## License

Eclipse Public License 1.0. See [LICENSE](LICENSE).
