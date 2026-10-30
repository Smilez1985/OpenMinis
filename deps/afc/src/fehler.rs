//! Uebersetzung der Antwort des Binders und der Fehlercodes.
//!
//! Die Codes der Plattform sind knapp und irrefuehrend ("null" bei 1001). Hier
//! steht zu jedem Code, was er auf diesem Geraet tatsaechlich geheissen hat
//! (gemessen am 29./30.10., siehe MESSUNGEN.md) und was zu tun ist.

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Art {
    Erfolg,
    Leer,
    Fehler,
    Zeitueberschreitung,
    Unklar,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Antwort {
    pub art: Art,
    pub json: Option<String>,
    pub code: Option<String>,
    pub kategorie: Option<String>,
    pub meldung: Option<String>,
    pub rohtext: Vec<String>,
}

impl Antwort {
    /// Wertet die Zeilen von `Ausfuehren` aus: "ERFOLG: …", "JSON: …",
    /// "FEHLER: code=… kat=… msg=…", "FERTIG", "ZEITUEBERSCHREITUNG".
    pub fn deute(roh: &str) -> Antwort {
        let mut a = Antwort {
            art: Art::Unklar,
            json: None,
            code: None,
            kategorie: None,
            meldung: None,
            rohtext: Vec::new(),
        };
        for zeile in roh.lines() {
            let z = zeile.trim();
            if z.is_empty() {
                continue;
            }
            a.rohtext.push(z.to_string());
            if let Some(rest) = z.strip_prefix("JSON: ") {
                a.json = Some(rest.to_string());
                a.art = Art::Erfolg;
            } else if let Some(rest) = z.strip_prefix("FEHLER:") {
                a.art = Art::Fehler;
                for teil in rest.split_whitespace() {
                    if let Some(v) = teil.strip_prefix("code=") {
                        a.code = Some(v.to_string());
                    } else if let Some(v) = teil.strip_prefix("kat=") {
                        a.kategorie = Some(v.to_string());
                    }
                }
                if let Some(i) = rest.find("msg=") {
                    a.meldung = Some(rest[i + 4..].trim().to_string());
                }
            } else if z.starts_with("ZEITUEBERSCHREITUNG") {
                a.art = Art::Zeitueberschreitung;
            } else if let Some(rest) = z.strip_prefix("ERFOLG:") {
                if rest.trim() == "null" || rest.trim().is_empty() {
                    a.art = Art::Leer;
                } else if a.art == Art::Unklar {
                    a.art = Art::Erfolg;
                }
            }
        }
        // Erfolg mit leerer Liste ist kein Treffer: gemessen liefern einige
        // Funktionen "ERFOLG" mit leerer Liste, auch wenn die Parameter falsch
        // waren. Das wird als eigener Zustand gefuehrt.
        if a.art == Art::Erfolg {
            if let Some(j) = &a.json {
                let t: String = j.chars().filter(|c| !c.is_whitespace()).collect();
                if t == "{}"
                    || t == "[]"
                    || t == "null"
                    || t.contains("\"androidAppfunctionsReturnValue\":[]")
                {
                    a.art = Art::Leer;
                }
            }
        }
        a
    }

    pub fn ist_erfolg(&self) -> bool {
        self.art == Art::Erfolg
    }
}

/// Eine Zeile je Code, wie er auf diesem Geraet gemessen wurde.
pub const CODES: &[(&str, &str)] = &[
    ("1000", "Aufrufer passt nicht: falsche uid, Paketname passt nicht zur uid, oder keine Berechtigung. Gemessen: Minis (uid 10278) bekommt 1000; uid 0 hat kein Paket; uid 2000 mit Paket com.android.shell geht."),
    ("1001", "Parameter falsch oder fehlt. Die Meldung ist oft nur 'null'. Gemessen: fehlendes/maxCount=-1 bei Fotos; leerer query bei findPersons."),
    ("1002", "Funktion ist deaktiviert (enabled=2 in den Laufzeit-Metadaten)."),
    ("1003", "Funktion nicht gefunden: falsche ID oder falsches Paket. Auch scope=activity-Funktionen antworten so."),
    ("1500", "Gemessen bei rejectCall ohne laufenden Anruf (vermutet, nicht belegt)."),
    ("2000", "Systemfehler der Ziel-App."),
    ("2001", "Aufruf abgebrochen."),
    ("2002", "Unternehmensrichtlinie (enterprise policy)."),
    ("3000", "Fehler in der Ziel-App selbst. Gemessen: getHistories ohne timeZone im DateTime."),
    ("3501", "Feature nicht verfuegbar. Gemessen an eingeschalteten gms-Funktionen, die die Plattform trotzdem ablehnt."),
    ("NO_PATH", "afc: kein Weg traegt (kein Binder zur Hand)."),
    ("NEEDS_CONFIRMATION", "afc: schreibende Funktion ohne --schreiben."),
    ("EMPTY_RESULT", "afc: ERFOLG, aber kein Inhalt - das Ergebnis ist unbekannt, nicht leer."),
];

pub fn erklaerung(code: &str) -> Option<&'static str> {
    CODES.iter().find(|(c, _)| *c == code).map(|(_, t)| *t)
}

/// Eine Zeile, die der Nutzer lesen kann.
pub fn bericht(a: &Antwort) -> String {
    match a.art {
        Art::Erfolg => match &a.json {
            Some(j) => format!("ERFOLG: {}", j),
            None => "ERFOLG".to_string(),
        },
        Art::Leer => "EMPTY_RESULT: ERFOLG, aber kein Inhalt. Das heisst nicht 'nichts da', \
                      sondern 'nicht nachgewiesen' - Parameter gegen die Registry pruefen."
            .to_string(),
        Art::Zeitueberschreitung => "ZEITUEBERSCHREITUNG: die Ziel-App hat nicht geantwortet."
            .to_string(),
        Art::Fehler => {
            let code = a.code.clone().unwrap_or_else(|| "?".to_string());
            let mut s = format!("FEHLER {}: {}", code, a.meldung.clone().unwrap_or_default());
            if let Some(k) = &a.kategorie {
                s.push_str(&format!(" (kat={})", k));
            }
            if let Some(e) = erklaerung(&code) {
                s.push_str(&format!("\n  {}", e));
            }
            s
        }
        Art::Unklar => format!(
            "UNKLAR: die Antwort war nicht zu deuten:\n  {}",
            a.rohtext.join("\n  ")
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fehler_mit_code_wird_uebersetzt() {
        let a = Antwort::deute("FEHLER: code=1001 kat=1 msg=query is null or empty\nFERTIG");
        assert_eq!(a.art, Art::Fehler);
        assert_eq!(a.code.as_deref(), Some("1001"));
        assert!(bericht(&a).contains("leerer query"));
    }

    #[test]
    fn json_ist_erfolg() {
        let a = Antwort::deute("JSON: {\"personIds\":[\"73\"]}\nFERTIG");
        assert!(a.ist_erfolg());
    }

    #[test]
    fn leerer_erfolg_ist_kein_treffer() {
        let a = Antwort::deute("JSON: []");
        assert_eq!(a.art, Art::Leer);
        assert!(bericht(&a).starts_with("EMPTY_RESULT"));
    }

    #[test]
    fn leere_rueckgabeliste_ist_kein_treffer() {
        // So sah die Antwort von findCallRecords am 29.10. aus: ERFOLG, aber leer.
        let a = Antwort::deute(
            "JSON: {\"androidAppfunctionsReturnValue\":[],\"_typ\":\"\"}\nFERTIG",
        );
        assert_eq!(a.art, Art::Leer);
    }

    #[test]
    fn erfolg_null_ist_leer() {
        let a = Antwort::deute("ERFOLG: null\nFERTIG");
        assert_eq!(a.art, Art::Leer);
    }

    #[test]
    fn codetabelle_ist_vollstaendig_und_eindeutig() {
        let mut gesehen = Vec::new();
        for (c, t) in CODES {
            assert!(!gesehen.contains(c), "Code {} ist doppelt", c);
            gesehen.push(*c);
            assert!(t.len() > 15, "Code {} hat keine Erklaerung", c);
        }
        assert!(erklaerung("3501").is_some());
        assert!(erklaerung("9999").is_none());
    }
}
