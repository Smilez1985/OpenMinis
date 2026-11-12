//! Translation of the binder reply and of the error codes.
//!
//! The platform's codes are terse and misleading ("null" for 1001). For each
//! code this lists what it actually meant on this device (measured on
//! 29/30 Oct 2026, see MESSUNGEN.md) and what to do about it.

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Kind {
    Success,
    Empty,
    Error,
    Timeout,
    Unclear,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Reply {
    pub kind: Kind,
    pub json: Option<String>,
    pub code: Option<String>,
    pub category: Option<String>,
    pub message: Option<String>,
    pub raw_lines: Vec<String>,
}

impl Reply {
    /// Interprets the lines printed by `Execute`: "SUCCESS: …", "JSON: …",
    /// "ERROR: code=… cat=… msg=…", "DONE", "TIMEOUT".
    pub fn interpret(raw: &str) -> Reply {
        let mut a = Reply {
            kind: Kind::Unclear,
            json: None,
            code: None,
            category: None,
            message: None,
            raw_lines: Vec::new(),
        };
        for line in raw.lines() {
            let z = line.trim();
            if z.is_empty() {
                continue;
            }
            a.raw_lines.push(z.to_string());
            if let Some(rest) = z.strip_prefix("JSON: ") {
                a.json = Some(rest.to_string());
                a.kind = Kind::Success;
            } else if let Some(rest) = z.strip_prefix("ERROR:") {
                a.kind = Kind::Error;
                for part in rest.split_whitespace() {
                    if let Some(v) = part.strip_prefix("code=") {
                        a.code = Some(v.to_string());
                    } else if let Some(v) = part.strip_prefix("cat=") {
                        a.category = Some(v.to_string());
                    }
                }
                if let Some(i) = rest.find("msg=") {
                    a.message = Some(rest[i + 4..].trim().to_string());
                }
            } else if z.starts_with("TIMEOUT") {
                a.kind = Kind::Timeout;
            } else if let Some(rest) = z.strip_prefix("SUCCESS:") {
                if rest.trim() == "null" || rest.trim().is_empty() {
                    a.kind = Kind::Empty;
                } else if a.kind == Kind::Unclear {
                    a.kind = Kind::Success;
                }
            }
        }
        // Success with an empty list is not a hit: measured, some functions
        // return "SUCCESS" with an empty list even when the parameters were
        // wrong. That is kept as a state of its own.
        if a.kind == Kind::Success {
            if let Some(j) = &a.json {
                let t: String = j.chars().filter(|c| !c.is_whitespace()).collect();
                if t == "{}"
                    || t == "[]"
                    || t == "null"
                    || t.contains("\"androidAppfunctionsReturnValue\":[]")
                {
                    a.kind = Kind::Empty;
                }
            }
        }
        a
    }

    pub fn is_success(&self) -> bool {
        self.kind == Kind::Success
    }
}

/// One line per code, as measured on this device.
pub const CODES: &[(&str, &str)] = &[
    ("1000", "Caller does not fit: wrong uid, package name does not match the uid, or no permission. Measured: Minis (uid 10278) gets 1000; uid 0 has no package; uid 2000 with package com.android.shell works."),
    ("1001", "Parameter wrong or missing. The message is often just 'null'. Measured: missing maxCount / maxCount=-1 for photos; empty query for findPersons."),
    ("1002", "Function is disabled (enabled=2 in the runtime metadata). Also returned on a locked device (\"Device is locked\"): unlock first, then try again."),
    ("1003", "Function not found: wrong id or wrong package. Functions with scope=activity answer this way too."),
    ("1500", "Measured for rejectCall without a running call (assumed, not proven)."),
    ("2000", "System error of the target app."),
    ("2001", "The call was cancelled."),
    ("2002", "Enterprise policy."),
    ("3000", "Error inside the target app itself. Measured: getHistories without timeZone in the DateTime."),
    ("3501", "Feature not available. Measured on enabled gms functions that the platform rejects anyway. Also returned with a locked screen (\"called while screen is locked\"): unlock first, then try again."),
    ("NO_CONSTRUCTOR", "The platform of this device declares no constructor for ExecuteAppFunctionAidlRequest that shim.jar knows (Request, UserHandle, String, then only long). The message lists the declared signatures; the selection in Execute has to be extended. Measured: 4 arguments on BP2A, 5 on BP4A."),
    ("NO_PATH", "afc: no route works (no binder at hand)."),
    ("NEEDS_CONFIRMATION", "afc: writing function without --write."),
    ("EMPTY_RESULT", "afc: SUCCESS, but no content - the result is unknown, not empty."),
];

pub fn explanation(code: &str) -> Option<&'static str> {
    CODES.iter().find(|(c, _)| *c == code).map(|(_, t)| *t)
}

/// Text the user can read.
pub fn report(a: &Reply) -> String {
    match a.kind {
        Kind::Success => match &a.json {
            Some(j) => format!("SUCCESS: {}", j),
            None => "SUCCESS".to_string(),
        },
        Kind::Empty => "EMPTY_RESULT: SUCCESS, but no content. That does not mean 'nothing there', \
                      it means 'not proven' - check the parameters against the registry."
            .to_string(),
        Kind::Timeout => "TIMEOUT: the target app did not answer."
            .to_string(),
        Kind::Error => {
            let code = a.code.clone().unwrap_or_else(|| "?".to_string());
            let mut s = format!("ERROR {}: {}", code, a.message.clone().unwrap_or_default());
            if let Some(k) = &a.category {
                s.push_str(&format!(" (cat={})", k));
            }
            if let Some(e) = explanation(&code) {
                s.push_str(&format!("\n  {}", e));
            }
            s
        }
        Kind::Unclear => format!(
            "UNCLEAR: the reply could not be interpreted:\n  {}",
            a.raw_lines.join("\n  ")
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_with_code_is_translated() {
        let a = Reply::interpret("ERROR: code=1001 cat=1 msg=query is null or empty\nDONE");
        assert_eq!(a.kind, Kind::Error);
        assert_eq!(a.code.as_deref(), Some("1001"));
        assert!(report(&a).contains("empty query"));
    }

    #[test]
    fn success_report_is_the_word_plus_the_json() {
        assert_eq!(report(&Reply::interpret("JSON: {\"a\":1}\nDONE")), "SUCCESS: {\"a\":1}");
        assert_eq!(report(&Reply::interpret("SUCCESS: x\nDONE")), "SUCCESS");
    }

    #[test]
    fn json_is_success() {
        let a = Reply::interpret("JSON: {\"personIds\":[\"73\"]}\nDONE");
        assert!(a.is_success());
    }

    #[test]
    fn empty_success_is_not_a_hit() {
        let a = Reply::interpret("JSON: []");
        assert_eq!(a.kind, Kind::Empty);
        assert!(report(&a).starts_with("EMPTY_RESULT"));
    }

    #[test]
    fn empty_return_list_is_not_a_hit() {
        // This is what the reply of findCallRecords looked like on 29 Oct 2026: SUCCESS, but empty.
        let a = Reply::interpret(
            "JSON: {\"androidAppfunctionsReturnValue\":[],\"_type\":\"\"}\nDONE",
        );
        assert_eq!(a.kind, Kind::Empty);
    }

    #[test]
    fn success_null_is_empty() {
        let a = Reply::interpret("SUCCESS: null\nDONE");
        assert_eq!(a.kind, Kind::Empty);
    }

    #[test]
    fn code_table_is_complete_and_unique() {
        let mut seen = Vec::new();
        for (c, t) in CODES {
            assert!(!seen.contains(c), "code {} is duplicated", c);
            seen.push(*c);
            assert!(t.len() > 15, "code {} has no explanation", c);
        }
        assert!(explanation("3501").is_some());
        assert!(explanation("9999").is_none());
    }

    #[test]
    fn no_constructor_is_reported_as_error_with_explanation() {
        let raw = "ERROR: code=NO_CONSTRUCTOR cat=0 msg=no matching constructor of X; declared: public X(A,B)\n";
        let a = Reply::interpret(raw);
        assert_eq!(a.kind, Kind::Error);
        assert_eq!(a.code.as_deref(), Some("NO_CONSTRUCTOR"));
        let b = report(&a);
        assert!(b.contains("declared: public X(A,B)"), "{}", b);
        assert!(b.contains("BP2A"), "{}", b);
    }

    #[test]
    fn timeout_line_is_a_timeout() {
        let a = Reply::interpret("TIMEOUT\n");
        assert_eq!(a.kind, Kind::Timeout);
        assert_eq!(report(&a), "TIMEOUT: the target app did not answer.");
    }

    #[test]
    fn unrecognised_lines_are_unclear_and_listed() {
        let a = Reply::interpret("WHATEVER: code=1001\nOK: {}\n");
        assert_eq!(a.kind, Kind::Unclear, "only the protocol words of the shim are understood");
        assert!(report(&a).starts_with("UNCLEAR: the reply could not be interpreted:\n  WHATEVER: code=1001\n  OK: {}"));
    }

    #[test]
    fn report_for_error_carries_code_category_and_explanation() {
        let a = Reply::interpret("ERROR: code=1002 cat=1 msg=disabled\nDONE");
        let r = report(&a);
        assert!(r.starts_with("ERROR 1002: disabled (cat=1)\n  Function is disabled"), "{}", r);
    }

    #[test]
    fn lock_is_mentioned_in_the_code_texts() {
        assert!(explanation("1002").unwrap().contains("unlock"));
        assert!(explanation("3501").unwrap().contains("unlock"));
    }
}
