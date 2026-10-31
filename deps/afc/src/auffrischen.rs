//! Die Registry auffrischen: `dumpsys app_function` lesen und daraus eine
//! Registry-Datei fuer DIESES Geraet schreiben.
//!
//! Regeln (jede mit Test):
//! * Welche Funktionen es gibt und ob sie an sind, bestimmt der Dump.
//! * Was die eingebettete Registry ueber eine Funktion schon weiss (Felder,
//!   Beispiele, Herkunft), bleibt erhalten - der Dump kennt die Felder in
//!   den Parameterklassen nicht.
//! * Eine Funktion, die der Dump neu bringt, bekommt nur, was der Dump sagt
//!   (Parametername, Klasse oder einfacher Typ, Herkunft `dump`). Ohne Felder
//!   prueft `afc` sie nicht tiefer; das steht im Ergebnis.
//! * `geprueft` wird immer `nein`: bestaetigt wurde ein Aufruf auf dem
//!   Messgeraet, nicht auf diesem.
//! * Eingebettete Funktionen, die der Dump nicht kennt, kommen nicht in die
//!   Datei und werden im Bericht genannt.

use std::collections::BTreeMap;

pub struct Ergebnis {
    pub text: String,
    pub neu: Vec<String>,
    pub behalten: usize,
    pub fehlen: Vec<String>,
}

struct Kopf {
    id: String,
    paket: String,
    kategorie: String,
    status: String,
    parameter: Vec<(String, String, String)>, // name, typ, ebene
}

/// Erster Wert von `"schluessel": ["wert"` ab `ab`, ohne Anfuehrungszeichen.
fn wert_nach(s: &str, schluessel: &str) -> Option<String> {
    let nadel = format!("\"{}\": [", schluessel);
    let i = s.find(&nadel)? + nadel.len();
    let rest = &s[i..];
    let ende = rest.find(']')?;
    Some(rest[..ende].trim().trim_matches('"').to_string())
}

fn parameter_lesen(block: &str) -> Vec<(String, String, String)> {
    let von = match block.find("\"parameters\": [") {
        Some(i) => i,
        None => return Vec::new(),
    };
    let bis = block[von..]
        .find("\"mobileApplicationQualifiedId\"")
        .map(|i| von + i)
        .unwrap_or(block.len());
    let region = &block[von..bis];
    let mut aus = Vec::new();
    // Jeder Parameter beginnt vor seinem "isRequired".
    let teile: Vec<&str> = region.split("\"isRequired\"").collect();
    for t in teile.iter().skip(1) {
        let name = match wert_nach(t, "name") {
            Some(n) if !n.is_empty() => n,
            _ => continue,
        };
        let rest = match t.find("\"dataTypeMetadata\"") {
            Some(i) => &t[i..],
            None => "",
        };
        let art = wert_nach(rest, "type").unwrap_or_default();
        let klasse = wert_nach(rest, "dataTypeReference").unwrap_or_default();
        let (typ, ebene) = if art == "11" && klasse.contains('.') {
            (klasse, String::new())
        } else if art == "8" {
            ("text".to_string(), "oberste-ebene".to_string())
        } else if art == "1" {
            ("boolean".to_string(), "oberste-ebene".to_string())
        } else if art == "10" {
            ("liste".to_string(), "oberste-ebene".to_string())
        } else {
            (String::new(), "oberste-ebene".to_string())
        };
        aus.push((name, typ, ebene));
    }
    aus
}

fn koepfe(dump: &str) -> Vec<Kopf> {
    let mut aus = Vec::new();
    for teil in dump.split("AppFunctionMetadata for: ").skip(1) {
        let id = teil.lines().next().unwrap_or("").trim().to_string();
        if id.is_empty() {
            continue;
        }
        let paket = wert_nach(teil, "packageName").unwrap_or_default();
        let kategorie = wert_nach(teil, "schemaCategory").unwrap_or_default();
        let laufzeit = teil.find("Runtime Metadata:").map(|i| &teil[i..]).unwrap_or("");
        let an = match wert_nach(laufzeit, "enabled").as_deref() {
            Some("1") => "an",
            Some("2") => "aus",
            _ => {
                if wert_nach(teil, "enabledByDefault").as_deref() == Some("true") {
                    "standard-an"
                } else {
                    "standard-aus"
                }
            }
        };
        let statisch = laufzeit_vorher(teil);
        aus.push(Kopf {
            id,
            paket,
            kategorie,
            status: an.to_string(),
            parameter: parameter_lesen(statisch),
        });
    }
    aus
}

/// Der statische Teil des Blocks (vor "Runtime Metadata:").
fn laufzeit_vorher(teil: &str) -> &str {
    match teil.find("Runtime Metadata:") {
        Some(i) => &teil[..i],
        None => teil,
    }
}

/// Die eingebetteten Zeilen je Funktions-ID.
fn bloecke(text: &str) -> BTreeMap<String, Vec<String>> {
    let mut m: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut aktuell: Option<String> = None;
    for z in text.lines() {
        if z.starts_with('#') || z.trim().is_empty() || z.starts_with("E\t") {
            continue;
        }
        let t: Vec<&str> = z.split('\t').collect();
        if t[0] == "F" && t.len() > 2 {
            aktuell = Some(t[2].to_string());
            m.insert(t[2].to_string(), vec![z.to_string()]);
        } else if let Some(id) = &aktuell {
            m.get_mut(id).unwrap().push(z.to_string());
        }
    }
    m
}

pub fn auffrischen(dump: &str, bisher: &str, geraet: &str) -> Ergebnis {
    let bekannt = bloecke(bisher);
    let mut gesehen: Vec<String> = Vec::new();
    let mut neu = Vec::new();
    let mut behalten = 0;
    let mut out = String::new();
    out.push_str("# AppFunctions-Registry, aufgefrischt aus dumpsys app_function.\n");
    out.push_str("# geprueft ist hier immer nein: bestaetigt wurde nur auf dem Messgeraet.\n");
    for k in koepfe(dump) {
        gesehen.push(k.id.clone());
        if let Some(zeilen) = bekannt.get(&k.id) {
            behalten += 1;
            for (i, z) in zeilen.iter().enumerate() {
                if i == 0 {
                    let mut t: Vec<String> = z.split('\t').map(|s| s.to_string()).collect();
                    while t.len() < 7 {
                        t.push(String::new());
                    }
                    t[5] = k.status.clone();
                    t[6] = "nein".to_string();
                    out.push_str(&t.join("\t"));
                } else {
                    out.push_str(z);
                }
                out.push('\n');
            }
        } else {
            neu.push(k.id.clone());
            let name = k.id.rsplit('#').next().unwrap_or(&k.id);
            out.push_str(&format!(
                "F\t{}\t{}\t{}\t{}\t{}\tnein\n",
                k.paket, k.id, name, k.kategorie, k.status
            ));
            for (pn, typ, ebene) in &k.parameter {
                out.push_str(&format!("P\t{}\t{}\t{}\t{}\tdump\n", k.id, pn, typ, ebene));
            }
        }
    }
    out.push_str(&format!("E\tgeraet\t{}\tregistry\t1\t\n", geraet));
    let fehlen = bekannt
        .keys()
        .filter(|id| !gesehen.contains(id))
        .cloned()
        .collect();
    Ergebnis { text: out, neu, behalten, fehlen }
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
  AppFunctionMetadata for: com.neu.app#sucheDinge
    Static Metadata:
      {
        "schemaCategory": ["dinge"]
        "enabledByDefault": [true]
        "parameters": [{
          "isRequired": [true]
          "name": ["sucheDingeParams"]
          "dataTypeMetadata": [{
            "dataTypeReference": ["com.x.SucheDingeParams"]
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

    fn lauf() -> Ergebnis {
        auffrischen(DUMP, crate::registry::REGISTRY, "gt")
    }

    #[test]
    fn bekannte_funktion_behaelt_felder_und_nimmt_status_aus_dump() {
        let e = lauf();
        let r = Registry::aus_text(&e.text);
        let f = r.finde(Some("com.sec.android.app.sbrowser"), "findBookmarks").unwrap();
        assert_eq!(f.status, "aus", "Status kommt aus dem Dump");
        assert!(!f.geprueft, "geprueft wird zurueckgesetzt");
        assert!(f.composite().unwrap().felder.iter().any(|x| x.name == "maxCount"));
        assert_eq!(e.behalten, 1);
    }

    #[test]
    fn neue_funktion_bekommt_nur_was_der_dump_sagt() {
        let e = lauf();
        assert_eq!(e.neu.len(), 2);
        let r = Registry::aus_text(&e.text);
        let f = r.finde(Some("com.neu.app"), "castMyScreen").unwrap();
        assert_eq!(f.status, "standard-aus");
        assert_eq!(f.parameter[0].name, "deviceName");
        assert_eq!(f.parameter[0].typ, "text");
        assert_eq!(f.parameter[0].ebene, "oberste-ebene");
        assert_eq!(f.parameter[0].quelle, "dump");
        let g = r.finde(Some("com.neu.app"), "sucheDinge").unwrap();
        assert_eq!(g.status, "an");
        assert_eq!(g.composite().unwrap().typ, "com.x.SucheDingeParams");
        assert!(g.composite().unwrap().felder.is_empty(), "Felder kennt der Dump nicht");
    }

    #[test]
    fn eingebettete_funktionen_ohne_dump_werden_genannt_und_nicht_uebernommen() {
        let e = lauf();
        let r = Registry::aus_text(&e.text);
        assert_eq!(r.funktionen.len(), 3);
        assert_eq!(e.fehlen.len(), 60);
        assert!(e.fehlen.iter().any(|i| i.contains("PersonFunctions_findPersons")));
    }

    #[test]
    fn leerer_dump_gibt_leere_registry() {
        let e = auffrischen("nichts", crate::registry::REGISTRY, "gt");
        assert_eq!(Registry::aus_text(&e.text).funktionen.len(), 0);
        assert_eq!(e.fehlen.len(), 61);
    }
}
