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
        return Err(format!("Shizuku-Weg fehlgeschlagen: {}", fehler));
    }
    Ok(combined)
}

#[cfg(test)]
mod tests {
    use super::*;

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
