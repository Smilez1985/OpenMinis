//! Validation before the call. The platform is lenient here: unknown fields
//! are silently dropped, missing required fields are replaced by defaults, and
//! a number given as text ("3") returns an empty list without an error. A
//! guessed call then looks like a search that found nothing.
//!
//! So `afc` first checks what the registry offers and names every anomaly with
//! a rule id (P0..P5). What the registry does not know is not checked but
//! reported as open.

use crate::json::Value;
use crate::registry::{Function, Registry};
use std::collections::BTreeMap;

/// Upper limit of the target apps: 50, 500 and 100000 returned at most 30 hits
/// (measured 29 Oct 2026 on contacts, photos, files).
pub const MAXCOUNT_LIMIT: i64 = 30;

#[derive(Debug, PartialEq)]
pub enum Severity {
    Warning,
    Error,
}

#[derive(Debug, PartialEq)]
pub struct Finding {
    pub rule: &'static str,
    pub severity: Severity,
    pub text: String,
}

#[derive(Debug, PartialEq)]
pub struct Check {
    pub findings: Vec<Finding>,
    /// The document the binder receives: parameter name -> value.
    pub document: BTreeMap<String, Value>,
    /// How `Execute` expects the call: parameter `-a3-` and the payload
    /// (`-a4-`). For a composite parameter that is its name and its content as
    /// an object; for top-level parameters it is `@` and the whole document.
    pub a3: String,
    pub a4: String,
}

impl Check {
    pub fn has_errors(&self) -> bool {
        self.findings.iter().any(|b| b.severity == Severity::Error)
    }

    pub fn errors(&self) -> Vec<&Finding> {
        self.findings.iter().filter(|b| b.severity == Severity::Error).collect()
    }
}

/// Builds the parameter document and validates while doing so.
///
/// * `input` are the fields as a human would write them (field name ->
///   value), without knowing whether they sit at the top level or inside a
///   composite parameter.
pub fn validate(f: &Function, input: &BTreeMap<String, Value>) -> Check {
    let mut findings = Vec::new();
    let mut document: BTreeMap<String, Value> = BTreeMap::new();
    let composite = f.composite();
    let flat = f.flat();

    // P0: function without parameter information - cannot be validated, and that is said so.
    if f.parameters.is_empty() {
        if !input.is_empty() {
            findings.push(Finding {
                rule: "P0",
                severity: Severity::Error,
                text: format!(
                    "'{}' declares no parameters; the registry knows nothing here. \
                     The given fields cannot be assigned: {}",
                    f.short_name(),
                    input.keys().cloned().collect::<Vec<_>>().join(", ")
                ),
            });
        } else {
            findings.push(Finding {
                rule: "P0",
                severity: Severity::Warning,
                text: format!(
                    "'{}' declares no parameters - nothing is validated. \
                     Read the actual state back after the call.",
                    f.short_name()
                ),
            });
        }
        return Check { findings, document, a3: "@".to_string(), a4: "{}".to_string() };
    }

    // Fields of the composite parameter
    let mut inner: BTreeMap<String, Value> = BTreeMap::new();
    for (name, value) in input.iter() {
        let fits_outside = flat.iter().any(|p| &p.name == name);
        let field = composite.and_then(|c| c.fields.iter().find(|x| &x.name == name));
        match (fits_outside, field) {
            (true, Some(_)) => {
                // Name defined twice - do not guess.
                findings.push(Finding {
                    rule: "P2",
                    severity: Severity::Error,
                    text: format!(
                        "'{}' exists as a top-level parameter AND as a field in '{}'. \
                         The registry has it twice; the call is not guessed.",
                        name,
                        composite.map(|c| c.name.as_str()).unwrap_or("?")
                    ),
                });
            }
            (true, None) => {
                let p = flat.iter().find(|p| &p.name == name).unwrap();
                if !p.type_name.is_empty() && !value.matches_type(&p.type_name) {
                    findings.push(Finding {
                        rule: "P3",
                        severity: Severity::Error,
                        text: format!(
                            "'{}' is declared as {}, but {} was given ({}). \
                             Measured: the target app silently accepts the wrong type \
                             and then returns an empty list.",
                            name,
                            p.type_name,
                            value.kind_name(),
                            value.write()
                        ),
                    });
                }
                document.insert(name.clone(), value.clone());
            }
            (false, Some(fd)) => {
                // P3: type
                if !fd.data_type.is_empty() && !value.matches_type(&fd.data_type) {
                    findings.push(Finding {
                        rule: "P3",
                        severity: Severity::Error,
                        text: format!(
                            "'{}' is declared as {}, but {} was given ({}). \
                             The target app silently accepts the wrong type \
                             and then returns e.g. an empty list.",
                            name,
                            fd.data_type,
                            value.kind_name(),
                            value.write()
                        ),
                    });
                }
                // P5: empty text where the target app requires content
                if let Value::Text(t) = value {
                    if t.is_empty() && f.requires_content() {
                        findings.push(Finding {
                            rule: "P5",
                            severity: Severity::Error,
                            text: format!("'{}' is empty; measured at this spot: error 1001 \"is null or empty\"", name),
                        });
                    }
                }
                // P4: maxCount
                if name == "maxCount" {
                    check_maxcount(value, &mut findings);
                }
                inner.insert(name.clone(), value.clone());
            }
            (false, None) => {
                let mut possible: Vec<String> = flat.iter().map(|p| p.name.clone()).collect();
                if let Some(c) = composite {
                    possible.extend(c.fields.iter().map(|x| x.name.clone()));
                }
                possible.sort();
                findings.push(Finding {
                    rule: "P2",
                    severity: Severity::Error,
                    text: format!(
                        "'{}' does not know '{}'. The platform would silently drop it. \
                         Possible: {}",
                        f.short_name(),
                        name,
                        if possible.is_empty() { "nothing known".to_string() } else { possible.join(", ") }
                    ),
                });
            }
        }
    }

    // P1: required fields of the composite parameter
    if let Some(c) = composite {
        for fd in c.fields.iter().filter(|x| x.required) {
            if !inner.contains_key(&fd.name) {
                findings.push(Finding {
                    rule: "P1",
                    severity: Severity::Error,
                    text: format!(
                        "Required field '{}' ({}) is missing. Origin of the information: {}",
                        fd.name, fd.data_type, fd.source
                    ),
                });
            }
        }
        let mut value = inner.clone();
        value.insert("_type".to_string(), Value::Text(c.type_name.clone()));
        document.insert(c.name.clone(), Value::Object(value));
    } else if !inner.is_empty() {
        // No composite parameter, but fields were assigned: then they are
        // parameters themselves. Can only happen if flat() was empty.
        for (k, v) in inner {
            document.insert(k, v);
        }
    }

    // Required top-level parameters
    for p in flat.iter() {
        if !document.contains_key(&p.name) && p.source == "call-ok" {
            findings.push(Finding {
                rule: "P1",
                severity: Severity::Warning,
                text: format!(
                    "Parameter '{}' is missing; this function was measured being called with it.",
                    p.name
                ),
            });
        }
    }

    let (a3, a4) = match composite {
        Some(c) => (
            c.name.clone(),
            document
                .get(&c.name)
                .cloned()
                .unwrap_or(Value::Object(BTreeMap::new()))
                .write(),
        ),
        None => ("@".to_string(), Value::Object(document.clone()).write()),
    };
    Check { findings, document, a3, a4 }
}

fn check_maxcount(value: &Value, findings: &mut Vec<Finding>) {
    match value {
        Value::Int(n) if *n > MAXCOUNT_LIMIT => {
            findings.push(Finding {
                rule: "P4",
                severity: Severity::Warning,
                text: format!(
                    "maxCount={} is above the limit {}; the target apps return at most {}. \
                     The value is set to {}.",
                    n, MAXCOUNT_LIMIT, MAXCOUNT_LIMIT, MAXCOUNT_LIMIT
                ),
            });
        }
        Value::Int(0) => {
            findings.push(Finding {
                rule: "P4",
                severity: Severity::Warning,
                text: "maxCount=0 returns an empty list - a hit cannot be proven this way."
                    .to_string(),
            });
        }
        Value::Int(n) if *n < 0 => {
            findings.push(Finding {
                rule: "P4",
                severity: Severity::Warning,
                text: "maxCount=-1 means 'all' for contacts, but error 1001 for photos, bookmarks \
                       and files. Use only after a visual check."
                    .to_string(),
            });
        }
        _ => {}
    }
}

impl Function {
    /// Contacts require a non-empty search text for `findPersons`.
    fn requires_content(&self) -> bool {
        self.name.eq_ignore_ascii_case("findPersons")
    }
}

/// Applies the notes that change the call (caps maxCount).
pub fn apply(document: &mut BTreeMap<String, Value>) {
    for value in document.values_mut() {
        if let Value::Object(m) = value {
            if let Some(Value::Int(n)) = m.get("maxCount").cloned() {
                if n > MAXCOUNT_LIMIT {
                    m.insert("maxCount".to_string(), Value::Int(MAXCOUNT_LIMIT));
                }
            }
        }
    }
}

/// Short form for display: looks the function up in the registry and validates.
pub fn validate_for(reg: &Registry, package: Option<&str>, key: &str, eingabe_json: &str) -> Result<Check, String> {
    let f = reg.find(package, key)?;
    let value = if eingabe_json.trim().is_empty() {
        Value::Object(BTreeMap::new())
    } else {
        Value::read(eingabe_json)?
    };
    let obj = match value {
        Value::Object(m) => m,
        Value::Null => BTreeMap::new(),
        _ => return Err("the arguments must be a JSON object".to_string()),
    };
    Ok(validate(f, &obj))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::Registry;

    fn obj(s: &str) -> BTreeMap<String, Value> {
        match Value::read(s).unwrap() {
            Value::Object(m) => m,
            _ => panic!("not an object"),
        }
    }

    fn fehler_regeln(p: &Check) -> Vec<&str> {
        p.errors().iter().map(|b| b.rule).collect()
    }

    #[test]
    fn valid_call_passes() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = validate(f, &obj("{\"query\":\"e\",\"maxCount\":3}"));
        assert_eq!(fehler_regeln(&p), Vec::<&str>::new(), "{:?}", p.findings);
        let doc = p.document.get("findPersonsParams").expect("composite parameter");
        match doc {
            Value::Object(m) => {
                assert_eq!(m["query"], Value::Text("e".into()));
                assert_eq!(m["maxCount"], Value::Int(3));
                assert_eq!(m["_type"], Value::Text("com.google.android.appfunctions.schema.common.v1.persons.FindPersonsParams".into()));
            }
            _ => panic!("not an object"),
        }
    }

    #[test]
    fn p1_required_field_missing() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = validate(f, &obj("{\"query\":\"e\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P1"]);
        assert!(p.errors()[0].text.contains("maxCount"));
    }

    #[test]
    fn p2_unknown_field_is_rejected() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = validate(f, &obj("{\"query\":\"e\",\"maxCount\":3,\"max\":3}"));
        assert_eq!(fehler_regeln(&p), vec!["P2"]);
        assert!(p.errors()[0].text.contains("max"));
    }

    #[test]
    fn p3_number_as_text_is_rejected() {
        // Counter-check to the device behaviour: the platform accepts "3" and
        // returns an empty list. The error has to stand out here.
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = validate(f, &obj("{\"query\":\"e\",\"maxCount\":\"3\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P3"]);
    }

    #[test]
    fn p4_maxcount_limit_is_a_note_and_gets_capped() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let mut p = validate(f, &obj("{\"query\":\"e\",\"maxCount\":500}"));
        assert!(!p.has_errors());
        assert!(p.findings.iter().any(|b| b.rule == "P4"));
        apply(&mut p.document);
        match &p.document["findPersonsParams"] {
            Value::Object(m) => assert_eq!(m["maxCount"], Value::Int(30)),
            _ => panic!("not an object"),
        }
    }

    #[test]
    fn p5_empty_search_text_for_contacts() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = validate(f, &obj("{\"query\":\"\",\"maxCount\":3}"));
        assert_eq!(fehler_regeln(&p), vec!["P5"]);
    }

    #[test]
    fn p0_function_without_parameter_information() {
        let r = Registry::embedded();
        // getPermissionsDeviceState has no parameter in the registry.
        let f = r.find(Some("com.google.android.permissioncontroller"), "getPermissionsDeviceState").unwrap();
        let p = validate(f, &obj("{}"));
        assert!(!p.has_errors());
        assert_eq!(p.findings[0].rule, "P0");
        let p2 = validate(f, &obj("{\"anything\":1}"));
        assert!(p2.has_errors());
    }

    #[test]
    fn flat_parameter_lands_at_top_level() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "getPersons").unwrap();
        let p = validate(f, &obj("{\"personIds\":[\"73\"]}"));
        assert!(!p.has_errors(), "{:?}", p.findings);
        assert_eq!(p.document["personIds"], Value::List(vec![Value::Text("73".into())]));
    }

    #[test]
    fn list_instead_of_text_is_a_type_error() {
        let r = Registry::embedded();
        let f = r.find(Some("com.samsung.android.app.contacts"), "getPersons").unwrap();
        let p = validate(f, &obj("{\"personIds\":\"73\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P3"]);
    }
}
