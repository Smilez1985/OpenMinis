//! afc — call AppFunctions: registry, validation, error translation, binder.
//!
//! Built from the measurements of 29/30 Oct 2026 on a test device (Android 16).
//! The registry lives in `data/registry.txt` and is embedded; every entry
//! carries its origin.
//!
//! Subcommands:
//!   list  [pattern] [--all] [--json]         show functions
//!   show  <name|id>                          one function with its parameters
//!   check <package|-> <name> [--args JSON]   validate only, call nothing
//!   call  <package|-> <name> [--args JSON] [--write] [--dry-run]
//!   probes                                   probes for the device test (TSV: package, name, args, confirmed)
//!   codes                                    explain error codes
//!   refresh [--dump FILE] [--out FILE]       renew this device's registry from dumpsys
//!
//! Writing functions only run with `--write`. The validation is the
//! guarantee, not the dialog: what the registry does not offer is reported as
//! open instead of guessed.

mod errors;
mod json;
mod probes;
mod refresh;
mod registry;
mod route;
mod validate;

use json::Value;
use registry::Registry;

const HELP: &str = "\
afc 0.1.0 — call AppFunctions (binder route, caller uid 2000)

  afc list [pattern] [--all] [--json]         functions from the registry
  afc show <name|id>                          one function with its parameters
  afc check <package|-> <name> [--args JSON]  validate, do not call
  afc call  <package|-> <name> [--args JSON] [--write] [--dry-run]
  afc codes                                   error codes and their meaning
  afc probes                                  probes of the device test (from the registry)
  afc refresh [--dump FILE] [--out FILE]      renew the registry from dumpsys app_function

  --all        also functions that are switched off
  --write      really call a writing function (otherwise validation only)
  --dry-run    prepare the call but do not execute it
  --registry FILE  registry instead of the embedded one ($AFC_REGISTRY; otherwise
               /var/minis/shared/afc/registry.txt if present)
  --jar PATH   shim.jar (takes precedence over $AFC_JAR; default /var/minis/shared/afc/shim.jar)
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let r = choose_registry(&args);
    let args = without_option(args, "--registry");
    let result = match args.first().map(|s| s.as_str()) {
        None | Some("--help") | Some("-h") | Some("help") => {
            println!("{}", HELP);
            Ok(())
        }
        Some("list") => list_cmd(&r, &args[1..]),
        Some("show") => show(&r, &args[1..]),
        Some("check") => check(&r, &args[1..], false),
        Some("call") => check(&r, &args[1..], true),
        Some("refresh") => refresh_cmd(&args[1..]),
        Some("probes") => {
            for p in probes::probes(&r) {
                println!("{}\t{}\t{}\t{}", p.package, p.name, p.args, if p.confirmed { "yes" } else { "no" });
            }
            Ok(())
        }
        Some("codes") => {
            for (c, t) in errors::CODES {
                println!("{:18} {}", c, t);
            }
            Ok(())
        }
        Some(other) => Err(format!("unknown subcommand '{}'\n\n{}", other, HELP)),
    };
    if let Err(e) = result {
        eprintln!("afc: {}", e);
        std::process::exit(2);
    }
}

fn list_cmd(r: &Registry, args: &[String]) -> Result<(), String> {
    let all = args.iter().any(|a| a == "--all");
    let as_json = args.iter().any(|a| a == "--json");
    let pattern = args
        .iter()
        .find(|a| !a.starts_with("--"))
        .map(|s| s.as_str())
        .unwrap_or("");
    let hits = r.search(pattern);
    if as_json {
        let mut s = String::from("[");
        for (i, f) in hits.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            s.push_str(&format!(
                "{{\"id\":{},\"package\":{},\"name\":{},\"category\":{},\"status\":{},\"verified\":{}}}",
                json::Value::Text(f.id.clone()).write(),
                json::Value::Text(f.package.clone()).write(),
                json::Value::Text(f.name.clone()).write(),
                json::Value::Text(f.category.clone()).write(),
                json::Value::Text(f.status.clone()).write(),
                f.verified
            ));
        }
        s.push(']');
        println!("{}", s);
        return Ok(());
    }
    let mut last_package = String::new();
    let mut shown = 0;
    for f in hits {
        if f.status == "off" && !all {
            continue;
        }
        if f.package != last_package {
            println!("\n{}", f.package);
            last_package = f.package.clone();
        }
        println!(
            "  {:<28} {:<14} {}{}",
            f.short_name(),
            if f.category.is_empty() { "-" } else { &f.category },
            if f.is_read_only() { "reads  " } else { "WRITES" },
            if f.verified { "  (measured)" } else { "" }
        );
        shown += 1;
    }
    println!(
        "\n{} functions shown (of {} in the registry{}).",
        shown,
        r.functions.len(),
        if all { "" } else { "; switched-off ones with --all" }
    );
    Ok(())
}

fn show(r: &Registry, args: &[String]) -> Result<(), String> {
    let name = args.first().ok_or("afc show <name|id>")?;
    let f = r.find(None, name)?;
    println!("{}", f.id);
    println!("  Package:    {}", f.package);
    println!("  Short name: {}", f.short_name());
    println!(
        "  Status:     {}{}",
        f.status,
        if f.verified { "  (measured on this device)" } else { "" }
    );
    if !f.category.is_empty() {
        println!("  Category:   {}", f.category);
    }
    println!(
        "  Kind:       {}",
        if f.is_read_only() { "reads (can be called without asking)" } else { "WRITES (only with --write)" }
    );
    if f.parameters.is_empty() {
        println!("  Parameters: none declared in the registry");
    } else {
        println!("  Parameters:");
        for p in &f.parameters {
            println!(
                "    {} ({}, {}, level={}, source={})",
                p.name,
                p.type_name,
                if p.fields.is_empty() { "fields unknown" } else { "" },
                if p.level.is_empty() { "inside the composite parameter" } else { &p.level },
                p.source
            );
            for fd in &p.fields {
                println!(
                    "      {:<18} {:<10} {:<6} source={}",
                    fd.name,
                    fd.data_type,
                    if fd.required { "required" } else { "-" },
                    fd.source
                );
            }
        }
        if let Some(b) = &f.example {
            println!("  Example:    {}", b);
        }
    }
    Ok(())
}

fn arg_value(args: &[String], name: &str) -> Option<String> {
    let i = args.iter().position(|a| a == name)?;
    args.get(i + 1).cloned()
}

fn check(r: &Registry, args: &[String], call_it: bool) -> Result<(), String> {
    let package = args.first().ok_or("afc check/call <package|-> <name> [--args JSON]")?;
    let name = args.get(1).ok_or("afc check/call <package|-> <name> [--args JSON]")?;
    let package = if package == "-" { None } else { Some(package.as_str()) };
    let f = r.find(package, name)?;
    let args_json = arg_value(args, "--args").unwrap_or_default();
    let write_allowed = args.iter().any(|a| a == "--write");
    let dry_run = args.iter().any(|a| a == "--dry-run");
    let jar = jar_choose(arg_value(args, "--jar"), std::env::var("AFC_JAR").ok());

    let p = validate::validate_for(r, package, name, &args_json)?;
    println!("{} ({})", f.short_name(), f.id);
    for b in &p.findings {
        println!(
            "  {} {}: {}",
            match b.severity {
                validate::Severity::Error => "ERROR",
                validate::Severity::Warning => "WARNING",
            },
            b.rule,
            b.text
        );
    }
    if p.has_errors() {
        println!("\nNo call made: {} rule violations.", p.errors().len());
        return Err("check failed".to_string());
    }
    let mut document = p.document.clone();
    validate::apply(&mut document);
    let doc = Value::Object(document).write();
    println!("  Document:  {}", doc);
    println!("  Call:      a3={} a4={}", p.a3, p.a4);

    if !f.is_read_only() && !write_allowed {
        println!(
            "\nNEEDS_CONFIRMATION: '{}' changes something. Ask first, then repeat with --write.",
            f.short_name()
        );
        return Ok(());
    }
    if !call_it {
        return Ok(());
    }
    let a = route::Call {
        package: &f.package,
        function: &f.id,
        parameters: p.a3.clone(),
        payload_json: p.a4.clone(),
        jar: &jar,
        caller: route::CALLER,
        dry_run,
    };
    if dry_run {
        println!("\n{}", route::script(&a)?);
        return Ok(());
    }
    let raw = route::execute(&a)?;
    let reply = errors::Reply::interpret(&raw);
    println!("\n{}", errors::report(&reply));
    if reply.is_success() {
        Ok(())
    } else {
        Err("call without success".to_string())
    }
}

/// Default location of `shim.jar` when neither `--jar` nor `AFC_JAR` is set.
const JAR_DEFAULT: &str = "/var/minis/shared/afc/shim.jar";

/// Order: `--jar`, then `AFC_JAR`, then the default. Empty values do not count.
fn jar_choose(arg: Option<String>, env: Option<String>) -> String {
    arg.filter(|s| !s.is_empty())
        .or_else(|| env.filter(|s| !s.is_empty()))
        .unwrap_or_else(|| JAR_DEFAULT.to_string())
}

#[cfg(test)]
mod tests_jar {
    use super::*;

    #[test]
    fn argument_beats_environment_beats_default() {
        let a = Some("/var/minis/a.jar".to_string());
        let u = Some("/var/minis/u.jar".to_string());
        assert_eq!(jar_choose(a.clone(), u.clone()), "/var/minis/a.jar");
        assert_eq!(jar_choose(None, u), "/var/minis/u.jar");
        assert_eq!(jar_choose(None, None), JAR_DEFAULT);
        assert_eq!(JAR_DEFAULT, "/var/minis/shared/afc/shim.jar");
        assert_eq!(jar_choose(Some(String::new()), None), JAR_DEFAULT);
        assert_eq!(jar_choose(a, Some(String::new())), "/var/minis/a.jar");
    }
}

/// Order: `--registry`, `$AFC_REGISTRY`, `/var/minis/shared/afc/registry.txt`
/// (only if present), otherwise the embedded one. A file that is named but
/// unreadable is an error, not a silent fallback.
fn choose_registry(args: &[String]) -> Registry {
    let named = arg_value(args, "--registry")
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("AFC_REGISTRY").ok().filter(|s| !s.is_empty()));
    let path = match &named {
        Some(p) => Some(p.clone()),
        None if std::path::Path::new(REGISTRY_DEFAULT).exists() => Some(REGISTRY_DEFAULT.to_string()),
        None => None,
    };
    match path {
        Some(p) => match std::fs::read_to_string(&p) {
            Ok(t) => Registry::from_text(&t),
            Err(e) => {
                eprintln!("afc: registry {} not readable: {}", p, e);
                std::process::exit(2);
            }
        },
        None => Registry::embedded(),
    }
}

const REGISTRY_DEFAULT: &str = "/var/minis/shared/afc/registry.txt";

fn refresh_cmd(args: &[String]) -> Result<(), String> {
    let dump = match arg_value(args, "--dump") {
        Some(p) => std::fs::read_to_string(&p).map_err(|e| format!("{}: {}", p, e))?,
        None => route::shell("dumpsys app_function")?,
    };
    if !dump.contains("AppFunctionMetadata for:") {
        return Err("the dump contains no AppFunctionMetadata - nothing written".to_string());
    }
    let out = arg_value(args, "--out").unwrap_or_else(|| REGISTRY_DEFAULT.to_string());
    let e = refresh::refresh(&dump, registry::REGISTRY, "refreshed");
    // Sidecar file + atomic rename, so a half-written file never sits there.
    let tmp = format!("{}.new", out);
    std::fs::write(&tmp, &e.text).map_err(|x| format!("{}: {}", tmp, x))?;
    std::fs::rename(&tmp, &out).map_err(|x| format!("{}: {}", out, x))?;
    let r = Registry::from_text(&e.text);
    println!("Registry written: {}", out);
    println!("  {} functions in the dump, {} of them with knowledge from the embedded registry, {} new (dump information only, fields unknown).", r.functions.len(), e.kept, e.added.len());
    if !e.missing.is_empty() {
        println!("  {} embedded functions are missing on this device (not carried over).", e.missing.len());
    }
    println!("  verified: 0 (confirmation happened only on the measuring device).");
    Ok(())
}

/// Removes `name VALUE` from the arguments so the value is not read as a
/// search pattern or subcommand.
fn without_option(args: Vec<String>, name: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < args.len() {
        if args[i] == name {
            i += 2;
        } else {
            out.push(args[i].clone());
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod tests_option {
    use super::without_option;
    #[test]
    fn option_with_its_value_is_dropped() {
        let a: Vec<String> = ["--registry", "/x", "list", "contact"].iter().map(|s| s.to_string()).collect();
        assert_eq!(without_option(a, "--registry"), vec!["list", "contact"]);
    }
}
