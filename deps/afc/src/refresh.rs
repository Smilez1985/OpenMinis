//! Refresh the registry: read `dumpsys app_function` and write a registry
//! file for THIS device from it.
//!
//! Rules (each with a test):
//! * The dump decides which functions exist and whether they are enabled.
//! * What the embedded registry already knows about a function (fields,
//!   examples, origin) is kept - the dump does not know the fields inside the
//!   parameter classes.
//! * A function that is new in the dump gets only what the dump says
//!   (parameter name, class or simple type, origin `dump`). Without fields
//!   `afc` does not validate it any deeper; the outcome says so.
//! * `verified` is always `no`: a call was confirmed on the measuring device,
//!   not on this one.
//! * Embedded functions the dump does not know do not go into the file and
//!   are named in the report.

use std::collections::BTreeMap;

pub struct Outcome {
    pub text: String,
    pub added: Vec<String>,
    pub kept: usize,
    pub missing: Vec<String>,
}

struct Head {
    id: String,
    package: String,
    category: String,
    status: String,
    parameters: Vec<(String, String, String)>, // name, type, level
}

/// First value of `"key": ["value"` in `s`, without quotation marks.
fn value_after(s: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\": [", key);
    let i = s.find(&needle)? + needle.len();
    let rest = &s[i..];
    let end = rest.find(']')?;
    Some(rest[..end].trim().trim_matches('"').to_string())
}

fn read_parameters(block: &str) -> Vec<(String, String, String)> {
    let from = match block.find("\"parameters\": [") {
        Some(i) => i,
        None => return Vec::new(),
    };
    let to = block[from..]
        .find("\"mobileApplicationQualifiedId\"")
        .map(|i| from + i)
        .unwrap_or(block.len());
    let region = &block[from..to];
    let mut out = Vec::new();
    // Each parameter starts before its "isRequired".
    let parts: Vec<&str> = region.split("\"isRequired\"").collect();
    for t in parts.iter().skip(1) {
        let name = match value_after(t, "name") {
            Some(n) if !n.is_empty() => n,
            _ => continue,
        };
        let rest = match t.find("\"dataTypeMetadata\"") {
            Some(i) => &t[i..],
            None => "",
        };
        let kind = value_after(rest, "type").unwrap_or_default();
        let class = value_after(rest, "dataTypeReference").unwrap_or_default();
        let (type_name, level) = if kind == "11" && class.contains('.') {
            (class, String::new())
        } else if kind == "8" {
            ("text".to_string(), "top-level".to_string())
        } else if kind == "1" {
            ("boolean".to_string(), "top-level".to_string())
        } else if kind == "10" {
            ("liste".to_string(), "top-level".to_string())
        } else {
            (String::new(), "top-level".to_string())
        };
        out.push((name, type_name, level));
    }
    out
}

fn heads(dump: &str) -> Vec<Head> {
    let mut out = Vec::new();
    for part in dump.split("AppFunctionMetadata for: ").skip(1) {
        let id = part.lines().next().unwrap_or("").trim().to_string();
        if id.is_empty() {
            continue;
        }
        let package = value_after(part, "packageName").unwrap_or_default();
        let category = value_after(part, "schemaCategory").unwrap_or_default();
        let runtime = part.find("Runtime Metadata:").map(|i| &part[i..]).unwrap_or("");
        let an = match value_after(runtime, "enabled").as_deref() {
            Some("1") => "on",
            Some("2") => "off",
            _ => {
                if value_after(part, "enabledByDefault").as_deref() == Some("true") {
                    "default-on"
                } else {
                    "default-off"
                }
            }
        };
        let static_part = before_runtime(part);
        out.push(Head {
            id,
            package,
            category,
            status: an.to_string(),
            parameters: read_parameters(static_part),
        });
    }
    out
}

/// The static part of the block (before "Runtime Metadata:").
fn before_runtime(part: &str) -> &str {
    match part.find("Runtime Metadata:") {
        Some(i) => &part[..i],
        None => part,
    }
}

/// The embedded lines per function id.
fn blocks(text: &str) -> BTreeMap<String, Vec<String>> {
    let mut m: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut current: Option<String> = None;
    for z in text.lines() {
        if z.starts_with('#') || z.trim().is_empty() || z.starts_with("E\t") {
            continue;
        }
        let t: Vec<&str> = z.split('\t').collect();
        if t[0] == "F" && t.len() > 2 {
            current = Some(t[2].to_string());
            m.insert(t[2].to_string(), vec![z.to_string()]);
        } else if let Some(id) = &current {
            m.get_mut(id).unwrap().push(z.to_string());
        }
    }
    m
}

pub fn refresh(dump: &str, bisher: &str, device: &str) -> Outcome {
    let known = blocks(bisher);
    let mut seen: Vec<String> = Vec::new();
    let mut added = Vec::new();
    let mut kept = 0;
    let mut out = String::new();
    out.push_str("# AppFunctions registry, refreshed from dumpsys app_function.\n");
    out.push_str("# verified is always no here: confirmation happened only on the measuring device.\n");
    for k in heads(dump) {
        seen.push(k.id.clone());
        if let Some(lines) = known.get(&k.id) {
            kept += 1;
            for (i, z) in lines.iter().enumerate() {
                if i == 0 {
                    let mut t: Vec<String> = z.split('\t').map(|s| s.to_string()).collect();
                    while t.len() < 7 {
                        t.push(String::new());
                    }
                    t[5] = k.status.clone();
                    t[6] = "no".to_string();
                    out.push_str(&t.join("\t"));
                } else {
                    out.push_str(z);
                }
                out.push('\n');
            }
        } else {
            added.push(k.id.clone());
            let name = k.id.rsplit('#').next().unwrap_or(&k.id);
            out.push_str(&format!(
                "F\t{}\t{}\t{}\t{}\t{}\tno\n",
                k.package, k.id, name, k.category, k.status
            ));
            for (pn, type_name, level) in &k.parameters {
                out.push_str(&format!("P\t{}\t{}\t{}\t{}\tdump\n", k.id, pn, type_name, level));
            }
        }
    }
    out.push_str(&format!("E\tdevice\t{}\tregistry\t1\t\n", device));
    let missing = known
        .keys()
        .filter(|id| !seen.contains(id))
        .cloned()
        .collect();
    Outcome { text: out, added, kept, missing }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::Registry;

    const DUMP: &str = r#"AppFunction state for user 0:
  AppFunctionMetadata for: com.sec.android.app.sbrowser.cross_app_action#BrowserFunctions_findBookmarks
    Static Metadata:
      {
        "schemaCategory": ["browser"]
        "packageName": ["com.sec.android.app.sbrowser"]
      }
    Runtime Metadata:
      {
        "enabled": [2]
        "functionId": ["x"]
      }
  AppFunctionMetadata for: com.neu.app#castMyScreen
    Static Metadata:
      {
        "enabledByDefault": [false]
        "parameters": [{
          "description": ["Name"]
          "isRequired": [true]
          "name": ["deviceName"]
          "dataTypeMetadata": [{
            "type": [8]
            "isNullable": [false]
          }
          ]
        }
        ]
        "mobileApplicationQualifiedId": ["a"]
        "packageName": ["com.neu.app"]
      }
    Runtime Metadata:
      {
        "functionId": ["com.neu.app#castMyScreen"]
      }
  AppFunctionMetadata for: com.neu.app#searchThings
    Static Metadata:
      {
        "schemaCategory": ["things"]
        "enabledByDefault": [true]
        "parameters": [{
          "isRequired": [true]
          "name": ["searchThingsParams"]
          "dataTypeMetadata": [{
            "dataTypeReference": ["com.x.SearchThingsParams"]
            "type": [11]
          }
          ]
        }
        ]
        "packageName": ["com.neu.app"]
      }
    Runtime Metadata:
      {
        "enabled": [1]
      }

App Function Agent Policy:
  Default Version : 1
"#;

    fn run_refresh() -> Outcome {
        refresh(DUMP, crate::registry::REGISTRY, "gt")
    }

    #[test]
    fn known_function_keeps_fields_and_takes_status_from_dump() {
        let e = run_refresh();
        let r = Registry::from_text(&e.text);
        let f = r.find(Some("com.sec.android.app.sbrowser"), "findBookmarks").unwrap();
        assert_eq!(f.status, "off", "status comes from the dump");
        assert!(!f.verified, "verified is reset");
        assert!(f.composite().unwrap().fields.iter().any(|x| x.name == "maxCount"));
        assert_eq!(e.kept, 1);
    }

    #[test]
    fn new_function_gets_only_what_the_dump_says() {
        let e = run_refresh();
        assert_eq!(e.added.len(), 2);
        let r = Registry::from_text(&e.text);
        let f = r.find(Some("com.neu.app"), "castMyScreen").unwrap();
        assert_eq!(f.status, "default-off");
        assert_eq!(f.parameters[0].name, "deviceName");
        assert_eq!(f.parameters[0].type_name, "text");
        assert_eq!(f.parameters[0].level, "top-level");
        assert_eq!(f.parameters[0].source, "dump");
        let g = r.find(Some("com.neu.app"), "searchThings").unwrap();
        assert_eq!(g.status, "on");
        assert_eq!(g.composite().unwrap().type_name, "com.x.SearchThingsParams");
        assert!(g.composite().unwrap().fields.is_empty(), "the dump does not know the fields");
    }

    #[test]
    fn embedded_functions_missing_from_dump_are_named_and_not_carried_over() {
        let e = run_refresh();
        let r = Registry::from_text(&e.text);
        assert_eq!(r.functions.len(), 3);
        assert_eq!(e.missing.len(), 60);
        assert!(e.missing.iter().any(|i| i.contains("PersonFunctions_findPersons")));
    }

    #[test]
    fn empty_dump_gives_empty_registry() {
        let e = refresh("nichts", crate::registry::REGISTRY, "gt");
        assert_eq!(Registry::from_text(&e.text).functions.len(), 0);
        assert_eq!(e.missing.len(), 61);
    }
}
