//! Runs the real `afc` binary: flags, subcommands and output words that other
//! components (the Kotlin app, tools/devicetest.sh) depend on.

use std::process::{Command, Output};

fn afc(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_afc"))
        .args(args)
        // Pin the registry so a file in /var/minis/shared/afc cannot change the result.
        .env("AFC_REGISTRY", concat!(env!("CARGO_MANIFEST_DIR"), "/data/registry.txt"))
        .output()
        .expect("afc starts")
}

fn out(o: &Output) -> String {
    format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr))
}

#[test]
fn help_lists_the_flags_and_subcommands() {
    let t = out(&afc(&["--help"]));
    for flag in ["--all", "--write", "--dry-run", "--out", "--registry", "--jar", "--args", "probes", "refresh"] {
        assert!(t.contains(flag), "help lacks {}: {}", flag, t);
    }
}

#[test]
fn probes_prints_tab_separated_lines_ending_in_yes_or_no() {
    let o = afc(&["probes"]);
    assert!(o.status.success());
    let t = out(&o);
    assert!(!t.is_empty());
    for line in t.lines() {
        let cols: Vec<&str> = line.split('\t').collect();
        assert_eq!(cols.len(), 4, "{}", line);
        assert!(cols[3] == "yes" || cols[3] == "no", "{}", line);
    }
}

#[test]
fn unknown_subcommand_is_an_error_with_exit_2() {
    let o = afc(&["frobnicate"]);
    assert_eq!(o.status.code(), Some(2));
    assert!(out(&o).contains("unknown subcommand 'frobnicate'"));
}

#[test]
fn list_hides_switched_off_functions_unless_all_is_given() {
    let hidden = out(&afc(&["list", "gms"]));
    let all = out(&afc(&["list", "gms", "--all"]));
    assert!(!hidden.contains("castMyScreen"), "{}", hidden);
    assert!(all.contains("castMyScreen"), "{}", all);
}

#[test]
fn writing_function_needs_write_flag() {
    let args = r#"{"displayName":"x"}"#;
    let without = out(&afc(&["call", "com.samsung.android.app.contacts", "createPerson", "--args", args]));
    assert!(without.contains("NEEDS_CONFIRMATION: 'PersonFunctions_createPerson' changes something. Ask first, then repeat with --write."), "{}", without);
}

#[test]
fn write_flag_lifts_the_confirmation_gate() {
    let args = r#"{"displayName":"x"}"#;
    let o = afc(&["call", "com.samsung.android.app.contacts", "createPerson", "--args", args, "--write", "--dry-run"]);
    let t = out(&o);
    assert!(o.status.success(), "{}", t);
    assert!(!t.contains("NEEDS_CONFIRMATION"), "{}", t);
    assert!(t.contains("app_process / Execute"), "{}", t);
}

#[test]
fn dry_run_prints_document_call_and_the_shim_command() {
    let args = r#"{"query":"e","maxCount":3}"#;
    let o = afc(&["call", "com.samsung.android.app.contacts", "findPersons", "--args", args, "--dry-run"]);
    assert!(o.status.success(), "{}", out(&o));
    let t = out(&o);
    assert!(t.contains("  Document:  {\"findPersonsParams\":{\"_type\":"), "{}", t);
    assert!(t.contains("  Call:      a3=findPersonsParams"), "{}", t);
    assert!(t.contains("app_process / Execute 'com.samsung.android.app.contacts'"), "{}", t);
    assert!(t.contains("/data/local/tmp/af/shim.jar"), "{}", t);
}

#[test]
fn failed_validation_reports_rule_violations_and_exits_2() {
    let o = afc(&["call", "com.samsung.android.app.contacts", "findPersons", "--args", r#"{"query":"e"}"#, "--dry-run"]);
    assert_eq!(o.status.code(), Some(2));
    let t = out(&o);
    assert!(t.contains("ERROR P1:"), "{}", t);
    assert!(t.contains("No call made: 1 rule violations."), "{}", t);
    assert!(t.contains("afc: check failed"), "{}", t);
}

#[test]
fn unknown_function_names_the_key() {
    let o = afc(&["show", "doesNotExist"]);
    assert_eq!(o.status.code(), Some(2));
    assert!(out(&o).contains("afc: no function matches 'doesNotExist'"));
}
