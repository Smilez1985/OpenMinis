//! Der Binder-Weg. Java (`aus.jar`) spricht den Binder, `afc` baut die Zeile
//! und deutet die Antwort.
//!
//! Warum nicht selbst Binder: das Parcel-Format waere nachzubauen und ist auf
//! diesem Geraet nicht belegt. Der Java-Teil ist gemessen (13 Aufrufe im
//! Erfolg), also bleibt er duenn und macht nur eines: den Aufruf.
//!
//! Aufrufer ist uid 2000 mit Paket `com.android.shell`. uid 0 scheitert (kein
//! Paket zur uid 0), die App selbst scheitert (EXECUTE_APP_FUNCTIONS ist
//! internal|privileged). Gemessen am 29.10.

use crate::json::Wert;
use std::process::Command;

/// Wer bin ich? Liest die uid aus `/proc/self/status` (Zeile `Uid:`).
pub fn eigene_uid() -> Option<u32> {
    let t = std::fs::read_to_string("/proc/self/status").ok()?;
    let z = t.lines().find(|l| l.starts_with("Uid:"))?;
    z.split_whitespace().nth(1)?.parse().ok()
}

/// Der Weg, den die Identitaet vorgibt.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Identitaet {
    /// uid 0: Root-Weg, `su 2000` setzt die Aufrufer-Identitaet.
    Root,
    /// uid 2000: Shizuku ohne Root, schon die richtige Identitaet.
    Shell,
    /// Alles andere (Sandkasten, App): nur ueber `android-shizuku-cli`.
    Fremd,
}

pub fn identitaet_von(uid: Option<u32>) -> Identitaet {
    match uid {
        Some(0) => Identitaet::Root,
        Some(2000) => Identitaet::Shell,
        _ => Identitaet::Fremd,
    }
}

/// Die Zeile, die `app_process` startet. Unter Root mit `su 2000`, unter
/// Shell direkt.
pub fn startzeile(i: Identitaet, jar: &str, paket: &str, fkt: &str, aufrufer: &str, a3: &str, doc: &str) -> String {
    let kern = format!(
        "CLASSPATH={} app_process / Ausfuehren '{}' '{}' {} '{}' b64:{}",
        jar, paket, fkt, aufrufer, a3, doc
    );
    match i {
        Identitaet::Root => format!("su 2000 -c \"{}\" 2>&1", kern),
        _ => format!("{} 2>&1", kern),
    }
}

/// Wie `Ausfuehren` den Aufrufer nennt, wenn nichts anderes gesagt wird.
pub const AUFRUFER: &str = "com.android.shell";

pub struct Aufruf<'a> {
    pub paket: &'a str,
    pub funktion: &'a str,
    /// `-a3-`: Parametername, oder `@` fuer die oberste Ebene.
    pub parameter: String,
    /// `-a4-`: die Nutzlast als JSON-Objekt.
    pub nutzlast_json: String,
    pub jar: &'a str,
    pub aufrufer: &'a str,
    pub zeit_s: u64,
    pub trocken: bool,
}

/// base64 (Standardalphabet, mit Auffuellzeichen). Kein crates.io im Sandkasten.
pub fn b64(daten: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::new();
    for stueck in daten.chunks(3) {
        let b = [stueck[0], *stueck.get(1).unwrap_or(&0), *stueck.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        s.push(T[(n >> 18) as usize & 63] as char);
        s.push(T[(n >> 12) as usize & 63] as char);
        s.push(if stueck.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        s.push(if stueck.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    s
}

/// Baut das Shell-Skript, das auf dem Geraet laeuft (ueber Shizuku als root).
/// Es sucht das Geraeteverzeichnis, legt `aus.jar` nach /data/local/tmp/af und
/// ruft als uid 2000 auf.
pub fn skript(a: &Aufruf) -> Result<String, String> {
    if a.paket.contains('\'') || a.funktion.contains('\'') {
        return Err("Paket oder Funktions-ID enthaelt ein Anfuehrungszeichen".to_string());
    }
    let rest = a
        .jar
        .strip_prefix("/var/minis/")
        .ok_or_else(|| format!("der Pfad zur jar muss unter /var/minis/ liegen: {}", a.jar))?;
    let doc = b64(a.nutzlast_json.as_bytes());
    Ok(format!(
        "basis=\"\"\n\
         for d in /data/user/0/com.openminis.app*/files/minis-global; do\n\
         \x20 if [ -f \"$d/{rest}\" ]; then basis=\"$d\"; break; fi\n\
         done\n\
         [ -n \"$basis\" ] || {{ echo \"afc: kein Geraeteverzeichnis mit {rest}\" >&2; exit 3; }}\n\
         mkdir -p /data/local/tmp/af\n\
         cp \"$basis/{rest}\" /data/local/tmp/af/aus.jar || exit 3\n\
         chmod 755 /data/local/tmp/af/aus.jar\n\
         su 2000 -c \"CLASSPATH=/data/local/tmp/af/aus.jar app_process / Ausfuehren '{paket}' '{fkt}' {aufrufer} '{a3}' b64:{doc}\" 2>&1\n",
        rest = rest,
        paket = a.paket,
        fkt = a.funktion,
        a3 = a.parameter,
        aufrufer = a.aufrufer,
        doc = doc
    ))
}

/// Fuehrt das Skript ueber `android-shizuku-cli` aus und gibt die Ausgabe
/// zurueck, die `Ausfuehren` gemacht hat.
pub fn ausfuehren(a: &Aufruf) -> Result<String, String> {
    let id = identitaet_von(eigene_uid());
    if id != Identitaet::Fremd && !a.trocken {
        return lokal(a, id);
    }
    let skript = skript(a)?;
    if a.trocken {
        return Ok(format!("(Trockenlauf) Skript:\n{}", skript));
    }
    let huelle = format!("echo {} | base64 -d | sh", b64(skript.as_bytes()));
    let aus = Command::new("android-shizuku-cli")
        .arg("exec")
        .arg(&huelle)
        .output()
        .map_err(|e| format!("android-shizuku-cli liess sich nicht starten: {}", e))?;
    let roh = String::from_utf8_lossy(&aus.stdout).to_string();
    let wert = Wert::lesen(&roh).map_err(|e| {
        format!(
            "Antwort von android-shizuku-cli nicht lesbar ({}): {}",
            e,
            roh.chars().take(400).collect::<String>()
        )
    })?;
    let (ok, daten) = match wert {
        Wert::Objekt(m) => (
            matches!(m.get("ok"), Some(Wert::Wahr(true))),
            m.get("data").cloned(),
        ),
        _ => (false, None),
    };
    let combined = match &daten {
        Some(Wert::Objekt(m)) => m.get("combined").and_then(|c| c.als_text()).unwrap_or("").to_string(),
        _ => String::new(),
    };
    if !ok {
        let fehler = match &daten {
            Some(Wert::Objekt(m)) => m.get("error").map(|e| e.schreiben()).unwrap_or_default(),
            _ => roh.chars().take(400).collect(),
        };
        return Err(format!("Shizuku-Weg fehlgeschlagen: {}", ursache(&fehler, &combined)));
    }
    Ok(combined)
}

/// Lauf auf dem Geraet selbst (uid 0 oder 2000): kein Shizuku-Umweg.
/// `aus.jar` muss lesbar liegen; unter uid 2000 ist der App-Ordner zu, darum
/// zuerst /data/local/tmp/af/aus.jar (weltlesbar).
fn lokal(a: &Aufruf, id: Identitaet) -> Result<String, String> {
    let jar = "/data/local/tmp/af/aus.jar";
    if !std::path::Path::new(jar).exists() {
        return Err(format!("{} fehlt: aus.jar einmal als root dorthin legen", jar));
    }
    let doc = b64(a.nutzlast_json.as_bytes());
    let zeile = startzeile(id, jar, a.paket, a.funktion, a.aufrufer, &a.parameter, &doc);
    let aus = Command::new("sh")
        .arg("-c")
        .arg(&zeile)
        .output()
        .map_err(|e| format!("sh liess sich nicht starten: {}", e))?;
    Ok(String::from_utf8_lossy(&aus.stdout).to_string())
}

/// Ein einzelner Befehl ueber Shizuku; gibt stdout zurueck.
pub fn shell(befehl: &str) -> Result<String, String> {
    // Auf dem Geraet selbst (uid 0 oder 2000) laeuft der Befehl direkt.
    if identitaet_von(eigene_uid()) != Identitaet::Fremd {
        let aus = Command::new("sh")
            .arg("-c")
            .arg(befehl)
            .output()
            .map_err(|e| format!("sh liess sich nicht starten: {}", e))?;
        return Ok(String::from_utf8_lossy(&aus.stdout).to_string());
    }
    let aus = Command::new("android-shizuku-cli")
        .arg("exec")
        .arg(befehl)
        .output()
        .map_err(|e| format!("android-shizuku-cli liess sich nicht starten: {}", e))?;
    let roh = String::from_utf8_lossy(&aus.stdout).to_string();
    let wert = Wert::lesen(&roh).map_err(|e| format!("Antwort nicht lesbar: {}", e))?;
    if let Wert::Objekt(m) = &wert {
        if let Some(Wert::Objekt(d)) = m.get("data") {
            if let Some(t) = d.get("stdout").and_then(|c| c.als_text()) {
                return Ok(t.to_string());
            }
        }
    }
    Err(format!("Shizuku-Weg fehlgeschlagen: {}", roh.chars().take(300).collect::<String>()))
}

/// Die Ursache fuer die Meldung: `error` von Shizuku, wenn es etwas sagt, sonst die
/// letzte nicht leere Zeile der Ausgabe (dort steht z. B. die Ausnahme von aus.jar).
/// Nie leer: ohne beides steht "keine Meldung".
fn ursache(fehler: &str, ausgabe: &str) -> String {
    let f = fehler.trim().trim_matches('"').trim();
    if !f.is_empty() && f != "null" {
        return f.to_string();
    }
    match ausgabe.lines().map(str::trim).filter(|z| !z.is_empty()).last() {
        Some(z) => z.chars().take(300).collect(),
        None => "keine Meldung (Ausgabe leer)".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn weiche_waehlt_nach_uid() {
        assert_eq!(identitaet_von(Some(0)), Identitaet::Root);
        assert_eq!(identitaet_von(Some(2000)), Identitaet::Shell);
        assert_eq!(identitaet_von(Some(10278)), Identitaet::Fremd);
        assert_eq!(identitaet_von(None), Identitaet::Fremd);
    }

    #[test]
    fn root_nimmt_su_shell_nimmt_direkt() {
        let r = startzeile(Identitaet::Root, "/j.jar", "p", "f", "com.android.shell", "@", "e30=");
        let d = startzeile(Identitaet::Shell, "/j.jar", "p", "f", "com.android.shell", "@", "e30=");
        assert!(r.starts_with("su 2000 -c"), "{}", r);
        assert!(!d.contains("su 2000"), "{}", d);
        assert!(d.starts_with("CLASSPATH=/j.jar app_process"), "{}", d);
    }

    #[test]
    fn ursache_nimmt_die_letzte_zeile_wenn_error_leer_ist() {
        let aus = "irgendwas\njava.lang.NoSuchMethodException: x\n\n";
        assert_eq!(ursache("", aus), "java.lang.NoSuchMethodException: x");
        assert_eq!(ursache("null", aus), "java.lang.NoSuchMethodException: x");
        assert_eq!(ursache("\"Boom\"", aus), "Boom");
        assert_eq!(ursache("", ""), "keine Meldung (Ausgabe leer)");
    }

    #[test]
    fn b64_stimmt_mit_bekannten_werten() {
        assert_eq!(b64(b""), "");
        assert_eq!(b64(b"f"), "Zg==");
        assert_eq!(b64(b"fo"), "Zm8=");
        assert_eq!(b64(b"foo"), "Zm9v");
        assert_eq!(b64("Straße".as_bytes()), "U3RyYcOfZQ==");
    }

    #[test]
    fn skript_traegt_paket_funktion_und_aufrufer() {
        let a = Aufruf {
            paket: "com.samsung.android.app.contacts",
            funktion: "com.samsung.android.contacts.person#PersonFunctions_findPersons",
            parameter: "findPersonsParams".to_string(),
            nutzlast_json: "{\"query\":\"e\"}".to_string(),
            jar: "/var/minis/shared/afc/aus.jar",
            aufrufer: AUFRUFER,
            zeit_s: 30,
            trocken: false,
        };
        let s = skript(&a).unwrap();
        assert!(s.contains("su 2000 -c"));
        assert!(s.contains("com.android.shell"));
        assert!(s.contains("shared/afc/aus.jar"));
        assert!(s.contains("b64:"));
        assert!(s.contains("'findPersonsParams'"));
    }

    #[test]
    fn pfad_ausserhalb_ist_ein_fehler() {
        let a = Aufruf {
            paket: "p",
            funktion: "f",
            parameter: "@".to_string(),
            nutzlast_json: "{}".to_string(),
            jar: "/tmp/aus.jar",
            aufrufer: AUFRUFER,
            zeit_s: 30,
            trocken: false,
        };
        assert!(skript(&a).is_err());
    }
}
