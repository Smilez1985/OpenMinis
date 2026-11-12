//! The binder route. Java (`shim.jar`) talks to the binder, `afc` builds the
//! command line and interprets the reply.
//!
//! Why not talk binder directly: the parcel format would have to be rebuilt
//! and is not proven on this device. The Java part is measured (13 calls that
//! succeeded), so it stays thin and does exactly one thing: the call.
//!
//! The caller is uid 2000 with package `com.android.shell`. uid 0 fails (no
//! package for uid 0), and the app itself fails (EXECUTE_APP_FUNCTIONS is
//! internal|privileged). Measured on 29 Oct 2026.

use crate::json::Value;
use std::process::Command;

/// Who am I? Reads the uid from `/proc/self/status` (line `Uid:`).
pub fn own_uid() -> Option<u32> {
    let t = std::fs::read_to_string("/proc/self/status").ok()?;
    let z = t.lines().find(|l| l.starts_with("Uid:"))?;
    z.split_whitespace().nth(1)?.parse().ok()
}

/// The route the identity dictates.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Identity {
    /// uid 0: root route, `su 2000` sets the caller identity.
    Root,
    /// uid 2000: Shizuku without root, already the right identity.
    Shell,
    /// Everything else (sandbox, app): only via `android-shizuku-cli`.
    Foreign,
}

pub fn identity_of(uid: Option<u32>) -> Identity {
    match uid {
        Some(0) => Identity::Root,
        Some(2000) => Identity::Shell,
        _ => Identity::Foreign,
    }
}

/// The line that starts `app_process`. Under root with `su 2000`, under
/// shell directly.
pub fn launch_line(i: Identity, jar: &str, package: &str, func: &str, caller: &str, a3: &str, doc: &str) -> String {
    let core = format!(
        "CLASSPATH={} app_process / Execute '{}' '{}' {} '{}' b64:{}",
        jar, package, func, caller, a3, doc
    );
    match i {
        Identity::Root => format!("su 2000 -c \"{}\" 2>&1", core),
        _ => format!("{} 2>&1", core),
    }
}

/// How `Execute` names the caller when nothing else is said.
pub const CALLER: &str = "com.android.shell";

pub struct Call<'a> {
    pub package: &'a str,
    pub function: &'a str,
    /// `-a3-`: parameter name, or `@` for the top level.
    pub parameters: String,
    /// `-a4-`: the payload as a JSON object.
    pub payload_json: String,
    pub jar: &'a str,
    pub caller: &'a str,
    pub dry_run: bool,
}

/// base64 (standard alphabet, with padding). No crates.io in the sandbox.
pub fn b64(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::new();
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        s.push(T[(n >> 18) as usize & 63] as char);
        s.push(T[(n >> 12) as usize & 63] as char);
        s.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        s.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    s
}

/// Builds the shell script that runs on the device (via Shizuku as root).
/// It finds the app data directory, copies `shim.jar` to /data/local/tmp/af
/// and makes the call as uid 2000.
pub fn script(a: &Call) -> Result<String, String> {
    if a.package.contains('\'') || a.function.contains('\'') {
        return Err("package or function id contains a quote character".to_string());
    }
    let rest = a
        .jar
        .strip_prefix("/var/minis/")
        .ok_or_else(|| format!("the path to the jar must be under /var/minis/: {}", a.jar))?;
    let doc = b64(a.payload_json.as_bytes());
    Ok(format!(
        "base=\"\"\n\
         for d in /data/user/0/com.openminis.app*/files/minis-global; do\n\
         \x20 if [ -f \"$d/{rest}\" ]; then base=\"$d\"; break; fi\n\
         done\n\
         [ -n \"$base\" ] || {{ echo \"afc: no app data directory containing {rest}\" >&2; exit 3; }}\n\
         mkdir -p /data/local/tmp/af\n\
         cp \"$base/{rest}\" /data/local/tmp/af/shim.jar || exit 3\n\
         chmod 755 /data/local/tmp/af/shim.jar\n\
         su 2000 -c \"CLASSPATH=/data/local/tmp/af/shim.jar app_process / Execute '{package}' '{func}' {caller} '{a3}' b64:{doc}\" 2>&1\n",
        rest = rest,
        package = a.package,
        func = a.function,
        a3 = a.parameters,
        caller = a.caller,
        doc = doc
    ))
}

/// Longest command line the Shizuku route passes through intact. Measured:
/// 8168 characters intact, 8198 truncated, and a truncated command silently
/// ends with exit 0. Calculated with a safety margin; the same measure as in
/// the app (`AppFunctionsBinder.COMMAND_LIMIT`).
pub const COMMAND_LIMIT: usize = 8000;

/// `Ok` if the line fits through the Shizuku route; otherwise a named error
/// instead of a silent truncation.
pub fn check_length(command: &str) -> Result<(), String> {
    if command.len() <= COMMAND_LIMIT {
        Ok(())
    } else {
        Err(format!(
            "afc-code=TOO_LONG: the command has {} characters, the Shizuku route silently truncates beyond {}. The arguments are too large.",
            command.len(),
            COMMAND_LIMIT
        ))
    }
}

/// Runs the script via `android-shizuku-cli` and returns the output that
/// `Execute` produced.
pub fn execute(a: &Call) -> Result<String, String> {
    let id = identity_of(own_uid());
    if id != Identity::Foreign && !a.dry_run {
        return run_local(a, id);
    }
    let script = script(a)?;
    if a.dry_run {
        return Ok(format!("(dry run) script:\n{}", script));
    }
    let wrapper = format!("echo {} | base64 -d | sh", b64(script.as_bytes()));
    check_length(&wrapper)?;
    let out = Command::new("android-shizuku-cli")
        .arg("exec")
        .arg(&wrapper)
        .output()
        .map_err(|e| format!("could not start android-shizuku-cli: {}", e))?;
    let raw = String::from_utf8_lossy(&out.stdout).to_string();
    let value = Value::read(&raw).map_err(|e| {
        format!(
            "reply of android-shizuku-cli not readable ({}): {}",
            e,
            raw.chars().take(400).collect::<String>()
        )
    })?;
    let (ok, data) = match value {
        Value::Object(m) => (
            matches!(m.get("ok"), Some(Value::Bool(true))),
            m.get("data").cloned(),
        ),
        _ => (false, None),
    };
    let combined = match &data {
        Some(Value::Object(m)) => m.get("combined").and_then(|c| c.as_text()).unwrap_or("").to_string(),
        _ => String::new(),
    };
    if !ok {
        let shizuku_error = match &data {
            Some(Value::Object(m)) => m.get("error").map(|e| e.write()).unwrap_or_default(),
            _ => raw.chars().take(400).collect(),
        };
        return Err(format!("Shizuku route failed: {}", cause(&shizuku_error, &combined)));
    }
    Ok(combined)
}

/// Where `shim.jar` sits on the device once the app has placed it.
pub const JAR_DEVICE: &str = "/data/local/tmp/af/shim.jar";

/// The chosen path (`--jar`, `$AFC_JAR`) if it exists, otherwise the device
/// path. `None` if neither exists.
pub fn jar_local(chosen: &str, exists: impl Fn(&str) -> bool) -> Option<String> {
    [chosen, JAR_DEVICE].into_iter().find(|p| !p.is_empty() && exists(p)).map(|p| p.to_string())
}

/// Run on the device itself (uid 0 or 2000): no Shizuku detour.
/// `shim.jar` must be readable; under uid 2000 the app folder is closed, hence
/// /data/local/tmp/af/shim.jar (world-readable) is used first.
fn run_local(a: &Call, id: Identity) -> Result<String, String> {
    let jar = match jar_local(a.jar, |p| std::path::Path::new(p).exists()) {
        Some(j) => j,
        None => return Err(format!("shim.jar is missing: neither {} nor {} (the app places it)", a.jar, JAR_DEVICE)),
    };
    let jar = jar.as_str();
    let doc = b64(a.payload_json.as_bytes());
    let line = launch_line(id, jar, a.package, a.function, a.caller, &a.parameters, &doc);
    let out = Command::new("sh")
        .arg("-c")
        .arg(&line)
        .output()
        .map_err(|e| format!("could not start sh: {}", e))?;
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}

/// A single command via Shizuku; returns stdout.
pub fn shell(command: &str) -> Result<String, String> {
    // On the device itself (uid 0 or 2000) the command runs directly.
    if identity_of(own_uid()) != Identity::Foreign {
        let out = Command::new("sh")
            .arg("-c")
            .arg(command)
            .output()
            .map_err(|e| format!("could not start sh: {}", e))?;
        return Ok(String::from_utf8_lossy(&out.stdout).to_string());
    }
    let out = Command::new("android-shizuku-cli")
        .arg("exec")
        .arg(command)
        .output()
        .map_err(|e| format!("could not start android-shizuku-cli: {}", e))?;
    let raw = String::from_utf8_lossy(&out.stdout).to_string();
    let value = Value::read(&raw).map_err(|e| format!("reply not readable: {}", e))?;
    if let Value::Object(m) = &value {
        if let Some(Value::Object(d)) = m.get("data") {
            if let Some(t) = d.get("stdout").and_then(|c| c.as_text()) {
                return Ok(t.to_string());
            }
        }
    }
    Err(format!("Shizuku route failed: {}", raw.chars().take(300).collect::<String>()))
}

/// The cause for the message: `error` from Shizuku if it says anything,
/// otherwise the last non-empty line of the output (that is where e.g. the
/// exception from shim.jar shows up). Never empty: with neither, it reads
/// "no message".
fn cause(errors: &str, output: &str) -> String {
    let f = errors.trim().trim_matches('"').trim();
    if !f.is_empty() && f != "null" {
        return f.to_string();
    }
    match output.lines().map(str::trim).filter(|z| !z.is_empty()).last() {
        Some(z) => z.chars().take(300).collect(),
        None => "no message (output empty)".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jar_local_prefers_the_chosen_path_then_the_device_path() {
        assert_eq!(jar_local("/x/shim.jar", |p| p == "/x/shim.jar"), Some("/x/shim.jar".to_string()));
        assert_eq!(jar_local("/x/shim.jar", |p| p == JAR_DEVICE), Some(JAR_DEVICE.to_string()));
        assert_eq!(jar_local("/x/shim.jar", |_| false), None);
        assert_eq!(jar_local("", |p| p == JAR_DEVICE), Some(JAR_DEVICE.to_string()));
    }

    #[test]
    fn launch_line_starts_the_execute_class_of_the_shim() {
        let d = launch_line(Identity::Shell, "/data/local/tmp/af/shim.jar", "p", "f", "com.android.shell", "@", "e30=");
        assert_eq!(d, "CLASSPATH=/data/local/tmp/af/shim.jar app_process / Execute 'p' 'f' com.android.shell '@' b64:e30= 2>&1");
    }

    #[test]
    fn route_is_chosen_by_uid() {
        assert_eq!(identity_of(Some(0)), Identity::Root);
        assert_eq!(identity_of(Some(2000)), Identity::Shell);
        assert_eq!(identity_of(Some(10278)), Identity::Foreign);
        assert_eq!(identity_of(None), Identity::Foreign);
    }

    #[test]
    fn root_uses_su_shell_runs_directly() {
        let r = launch_line(Identity::Root, "/j.jar", "p", "f", "com.android.shell", "@", "e30=");
        let d = launch_line(Identity::Shell, "/j.jar", "p", "f", "com.android.shell", "@", "e30=");
        assert!(r.starts_with("su 2000 -c"), "{}", r);
        assert!(!d.contains("su 2000"), "{}", d);
        assert!(d.starts_with("CLASSPATH=/j.jar app_process"), "{}", d);
    }

    #[test]
    fn cause_takes_the_last_line_when_error_is_empty() {
        let out = "irgendwas\njava.lang.NoSuchMethodException: x\n\n";
        assert_eq!(cause("", out), "java.lang.NoSuchMethodException: x");
        assert_eq!(cause("null", out), "java.lang.NoSuchMethodException: x");
        assert_eq!(cause("\"Boom\"", out), "Boom");
        assert_eq!(cause("", ""), "no message (output empty)");
    }

    #[test]
    fn b64_matches_known_values() {
        assert_eq!(b64(b""), "");
        assert_eq!(b64(b"f"), "Zg==");
        assert_eq!(b64(b"fo"), "Zm8=");
        assert_eq!(b64(b"foo"), "Zm9v");
        assert_eq!(b64("café".as_bytes()), "Y2Fmw6k=");
    }

    #[test]
    fn script_carries_package_function_and_caller() {
        let a = Call {
            package: "com.samsung.android.app.contacts",
            function: "com.samsung.android.contacts.person#PersonFunctions_findPersons",
            parameters: "findPersonsParams".to_string(),
            payload_json: "{\"query\":\"e\"}".to_string(),
            jar: "/var/minis/shared/afc/shim.jar",
            caller: CALLER,
            dry_run: false,
        };
        let s = script(&a).unwrap();
        assert!(s.contains("su 2000 -c"));
        assert!(s.contains("com.android.shell"));
        assert!(s.contains("shared/afc/shim.jar"));
        assert!(s.contains("b64:"));
        assert!(s.contains("'findPersonsParams'"));
    }

    #[test]
    fn path_outside_is_an_error() {
        let a = Call {
            package: "p",
            function: "f",
            parameters: "@".to_string(),
            payload_json: "{}".to_string(),
            jar: "/tmp/shim.jar",
            caller: CALLER,
            dry_run: false,
        };
        assert!(script(&a).is_err());
    }

    #[test]
    fn length_exactly_at_the_limit_passes_one_over_does_not() {
        assert!(check_length(&"x".repeat(COMMAND_LIMIT)).is_ok());
        let e = check_length(&"x".repeat(COMMAND_LIMIT + 1)).unwrap_err();
        assert!(e.contains("TOO_LONG") && e.contains("8001"), "{}", e);
    }

    #[test]
    fn limit_is_below_the_measured_8192() {
        assert!(COMMAND_LIMIT < 8192 && COMMAND_LIMIT <= 8168);
    }

    #[test]
    fn the_wrapper_with_5000_characters_of_payload_would_be_too_long() {
        let a = Call {
            package: "p.k", function: "p.k#f", caller: "com.android.shell", parameters: "@".to_string(),
            payload_json: "x".repeat(5000), jar: "/var/minis/shared/afc/shim.jar", dry_run: false,
        };
        let sk = script(&a).unwrap();
        let wrapper = format!("echo {} | base64 -d | sh", b64(sk.as_bytes()));
        assert!(check_length(&wrapper).is_err(), "wrapper has {} characters", wrapper.len());
    }
}
