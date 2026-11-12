//! Probes for the device test, derived from the registry of THIS device.
//!
//! Rules:
//! * only read-only functions, only enabled ones (`on`, `default-on`);
//! * the composite parameter may only have `query` (string) and `maxCount`
//!   (long) as required fields - anything else needs values we do not know,
//!   and is not guessed;
//! * a composite parameter without known fields only counts if the registry
//!   knows a confirmed call with an empty document for the function
//!   (`call-ok`, arguments `-`);
//! * functions with top-level parameters (e.g. `personIds`) need real ids and
//!   are dropped.
//!
//! So the device decides which apps are probed, not a fixed list.

use crate::registry::{Function, Registry};

pub struct Probe {
    pub package: String,
    pub name: String,
    pub args: String,
    /// `true`: exactly this form ran on the measuring device (embedded
    /// registry, `verified`, example not given as `callj`). `false`: derived
    /// from the shape, so a failure is a deviation, not proof against the route.
    pub confirmed: bool,
}

pub fn probes(r: &Registry) -> Vec<Probe> {
    let measured = Registry::embedded();
    let mut out = Vec::new();
    for f in &r.functions {
        if let Some(a) = args_for(f) {
            let confirmed = measured
                .functions
                .iter()
                .any(|m| m.id == f.id && m.verified && !m.example_args.starts_with("callj"));
            out.push(Probe { package: f.package.clone(), name: f.short_name().to_string(), args: a, confirmed });
        }
    }
    out
}

fn args_for(f: &Function) -> Option<String> {
    if !f.is_read_only() || !(f.status == "on" || f.status == "default-on") {
        return None;
    }
    if f.parameters.is_empty() || !f.flat().is_empty() {
        return None;
    }
    let c = f.composite()?;
    if c.fields.is_empty() {
        let empty_confirmed = f.example_args == "-" && f.example_source == "call-ok";
        return if empty_confirmed { Some("{}".to_string()) } else { None };
    }
    let mut parts = Vec::new();
    for fd in &c.fields {
        match (fd.name.as_str(), fd.data_type.as_str()) {
            ("query", "string") => parts.push("\"query\":\"a\"".to_string()),
            ("maxCount", "long") => parts.push("\"maxCount\":3".to_string()),
            _ if fd.required => return None,
            _ => {}
        }
    }
    Some(format!("{{{}}}", parts.join(",")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(text: &str) -> Registry {
        Registry::from_text(text)
    }

    const HEAD: &str = "F\tp\tp#findX\tfindX\tk\ton\tno\n";

    #[test]
    fn query_and_maxcount_are_filled() {
        let t = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\tmaxCount\tlong\tyes\tschema\nD\tp#findX\tfParams\tquery\tstring\tno\tschema\n", HEAD);
        let p = probes(&r(&t));
        assert_eq!(p.len(), 1);
        assert_eq!(p[0].args, "{\"maxCount\":3,\"query\":\"a\"}");
    }

    #[test]
    fn foreign_required_field_drops_the_function() {
        let t = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\ttimeZone\tstring\tyes\tschema\n", HEAD);
        assert!(probes(&r(&t)).is_empty());
    }

    #[test]
    fn unknown_fields_only_with_confirmed_empty_call() {
        let without = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\n", HEAD);
        assert!(probes(&r(&without)).is_empty(), "without evidence nothing is guessed");
        let with = format!("{}B\tp#findX\t-\tcall-ok\tx\n", format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\n", HEAD));
        assert_eq!(probes(&r(&with))[0].args, "{}");
    }

    #[test]
    fn writing_disabled_and_top_level_are_dropped() {
        let writes = "F\tp\tp#setX\tsetX\tk\ton\tno\nP\tp#setX\txParams\ta.b.XParams\t\tdump\nD\tp#setX\txParams\tmaxCount\tlong\tno\tschema\n";
        assert!(probes(&r(writes)).is_empty());
        let out = "F\tp\tp#findX\tfindX\tk\toff\tno\nP\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\tmaxCount\tlong\tyes\tschema\n";
        assert!(probes(&r(out)).is_empty());
        let flat = "F\tp\tp#getX\tgetX\tk\ton\tno\nP\tp#getX\tids\tlist-of-text\ttop-level\tassumed\n";
        assert!(probes(&r(flat)).is_empty());
    }

    #[test]
    fn confirmed_means_measured_in_this_shape() {
        let p = probes(&Registry::embedded());
        let by_name = |n: &str| p.iter().find(|x| x.name.ends_with(n)).unwrap();
        assert!(by_name("getDeviceStateMetadata").confirmed);
        assert!(by_name("PersonFunctions_findPersons").confirmed);
        assert!(!by_name("getHistories").confirmed, "measured with different arguments (callj)");
        assert!(!by_name("findTabs").confirmed, "never confirmed, 1001 with maxCount alone");
    }

    #[test]
    fn embedded_registry_gives_probes_without_samsung_requirement() {
        let p = probes(&Registry::embedded());
        let names: Vec<&str> = p.iter().map(|x| x.name.as_str()).collect();
        assert!(names.contains(&"getDeviceStateMetadata"), "{:?}", names);
        assert!(names.contains(&"getUncategorizedDeviceState"), "{:?}", names);
        assert!(names.iter().any(|n| n.ends_with("findPersons")), "{:?}", names);
        assert!(p.iter().all(|x| x.args.starts_with('{')));
    }
}
