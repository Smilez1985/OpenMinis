//! Pruefung vor dem Aufruf. Die Plattform ist hier nachsichtig: unbekannte
//! Felder werden still verworfen, fehlende Pflichtfelder durch Vorgaben
//! ersetzt, eine Zahl als Text ("3") liefert eine leere Liste ohne Fehler. Ein
//! geratener Aufruf sieht dann aus wie ein Treffer ohne Ergebnis.
//!
//! Deshalb prueft `afc` vorher, was die Registry hergibt, und benennt jede
//! Auffaelligkeit mit einer Regelkennung (P1..P7). Was die Registry nicht
//! weiss, wird nicht geprueft, sondern als offen gemeldet.

use crate::json::Wert;
use crate::registry::{Funktion, Registry};
use std::collections::BTreeMap;

/// Obergrenze der Ziel-Apps: 50, 500, 100000 lieferten hoechstens 30 Treffer
/// (gemessen 29.10. an Kontakten, Fotos, Dateien).
pub const MAXCOUNT_OBERGRENZE: i64 = 30;

#[derive(Debug, PartialEq)]
pub enum Schwere {
    Hinweis,
    Fehler,
}

#[derive(Debug, PartialEq)]
pub struct Befund {
    pub regel: &'static str,
    pub schwere: Schwere,
    pub text: String,
}

#[derive(Debug, PartialEq)]
pub struct Pruefung {
    pub befunde: Vec<Befund>,
    /// Das Dokument, das der Binder bekommt: Parametername -> Wert.
    pub dokument: BTreeMap<String, Wert>,
    /// Wie `Ausfuehren` den Aufruf erwartet: Parameter `-a3-` und die Nutzlast
    /// (`-a4-`). Bei einem Sammelparameter ist das sein Name und der Inhalt als
    /// Objekt; bei Parametern auf der obersten Ebene ist es `@` und das ganze
    /// Dokument.
    pub a3: String,
    pub a4: String,
}

impl Pruefung {
    pub fn hat_fehler(&self) -> bool {
        self.befunde.iter().any(|b| b.schwere == Schwere::Fehler)
    }

    pub fn fehler(&self) -> Vec<&Befund> {
        self.befunde.iter().filter(|b| b.schwere == Schwere::Fehler).collect()
    }
}

/// Baut das Parameterdokument und prueft dabei.
///
/// * `eingabe` sind die Felder, wie sie ein Mensch schreiben wuerde
///   (Feldname -> Wert), ohne zu wissen, ob sie oben oder in einem
///   Sammelparameter liegen.
pub fn pruefen(f: &Funktion, eingabe: &BTreeMap<String, Wert>) -> Pruefung {
    let mut befunde = Vec::new();
    let mut dokument: BTreeMap<String, Wert> = BTreeMap::new();
    let composite = f.composite();
    let flach = f.flach();

    // P0: Funktion ohne Parameterangabe - nicht pruefbar, das wird gesagt.
    if f.parameter.is_empty() {
        if !eingabe.is_empty() {
            befunde.push(Befund {
                regel: "P0",
                schwere: Schwere::Fehler,
                text: format!(
                    "'{}' gibt keine Parameter an; die Registry kennt hier nichts. \
                     Angegebene Felder koennen nicht zugeordnet werden: {}",
                    f.kurzname(),
                    eingabe.keys().cloned().collect::<Vec<_>>().join(", ")
                ),
            });
        } else {
            befunde.push(Befund {
                regel: "P0",
                schwere: Schwere::Hinweis,
                text: format!(
                    "'{}' gibt keine Parameter an - es wird nichts geprueft. \
                     Nach dem Aufruf den Ist-Zustand gegenlesen.",
                    f.kurzname()
                ),
            });
        }
        return Pruefung { befunde, dokument, a3: "@".to_string(), a4: "{}".to_string() };
    }

    // Felder des Sammelparameters
    let mut innen: BTreeMap<String, Wert> = BTreeMap::new();
    for (name, wert) in eingabe.iter() {
        let passt_aussen = flach.iter().any(|p| &p.name == name);
        let feld = composite.and_then(|c| c.felder.iter().find(|x| &x.name == name));
        match (passt_aussen, feld) {
            (true, Some(_)) => {
                // Name doppelt belegt - nicht raten.
                befunde.push(Befund {
                    regel: "P2",
                    schwere: Schwere::Fehler,
                    text: format!(
                        "'{}' gibt es als Parameter auf der obersten Ebene UND als Feld in '{}'. \
                         Das ist in der Registry doppelt; der Aufruf wird nicht geraten.",
                        name,
                        composite.map(|c| c.name.as_str()).unwrap_or("?")
                    ),
                });
            }
            (true, None) => {
                let p = flach.iter().find(|p| &p.name == name).unwrap();
                if !p.typ.is_empty() && !wert.passt_zu(&p.typ) {
                    befunde.push(Befund {
                        regel: "P3",
                        schwere: Schwere::Fehler,
                        text: format!(
                            "'{}' ist als {} angegeben, angegeben wurde {} ({}). \
                             Gemessen: die Ziel-App nimmt den falschen Typ stillschweigend an \
                             und liefert dann eine leere Liste.",
                            name,
                            p.typ,
                            wert.art_name(),
                            wert.schreiben()
                        ),
                    });
                }
                dokument.insert(name.clone(), wert.clone());
            }
            (false, Some(fd)) => {
                // P3: Typ
                if !fd.datentyp.is_empty() && !wert.passt_zu(&fd.datentyp) {
                    befunde.push(Befund {
                        regel: "P3",
                        schwere: Schwere::Fehler,
                        text: format!(
                            "'{}' ist als {} angegeben, angegeben wurde {} ({}). \
                             Die Ziel-App nimmt den falschen Typ stillschweigend an \
                             und liefert dann z. B. eine leere Liste.",
                            name,
                            fd.datentyp,
                            wert.art_name(),
                            wert.schreiben()
                        ),
                    });
                }
                // P5: leerer Text, wo die Ziel-App einen Inhalt verlangt
                if let Wert::Text(t) = wert {
                    if t.is_empty() && f.shortname_verlangt_inhalt() {
                        befunde.push(Befund {
                            regel: "P5",
                            schwere: Schwere::Fehler,
                            text: format!("'{}' ist leer; gemessen an dieser Stelle: Fehler 1001 \"is null or empty\"", name),
                        });
                    }
                }
                // P4: maxCount
                if name == "maxCount" {
                    pruefe_maxcount(wert, &mut befunde);
                }
                innen.insert(name.clone(), wert.clone());
            }
            (false, None) => {
                let mut moeglich: Vec<String> = flach.iter().map(|p| p.name.clone()).collect();
                if let Some(c) = composite {
                    moeglich.extend(c.felder.iter().map(|x| x.name.clone()));
                }
                moeglich.sort();
                befunde.push(Befund {
                    regel: "P2",
                    schwere: Schwere::Fehler,
                    text: format!(
                        "'{}' kennt '{}' nicht. Die Plattform wuerde es still verwerfen. \
                         Moeglich sind: {}",
                        f.kurzname(),
                        name,
                        if moeglich.is_empty() { "nichts Bekanntes".to_string() } else { moeglich.join(", ") }
                    ),
                });
            }
        }
    }

    // P1: Pflichtfelder des Sammelparameters
    if let Some(c) = composite {
        for fd in c.felder.iter().filter(|x| x.pflicht) {
            if !innen.contains_key(&fd.name) {
                befunde.push(Befund {
                    regel: "P1",
                    schwere: Schwere::Fehler,
                    text: format!(
                        "Pflichtfeld '{}' ({}) fehlt. Herkunft der Angabe: {}",
                        fd.name, fd.datentyp, fd.quelle
                    ),
                });
            }
        }
        let mut wert = innen.clone();
        wert.insert("_typ".to_string(), Wert::Text(c.typ.clone()));
        dokument.insert(c.name.clone(), Wert::Objekt(wert));
    } else if !innen.is_empty() {
        // Kein Sammelparameter, aber Felder wurden zugeordnet: dann sind sie
        // selbst Parameter. Kann nur passieren, wenn flach() leer war.
        for (k, v) in innen {
            dokument.insert(k, v);
        }
    }

    // Pflicht-Parameter der obersten Ebene
    for p in flach.iter() {
        if !dokument.contains_key(&p.name) && p.quelle == "aufruf-ok" {
            befunde.push(Befund {
                regel: "P1",
                schwere: Schwere::Hinweis,
                text: format!(
                    "Parameter '{}' fehlt; gemessen wurde diese Funktion mit ihm aufgerufen.",
                    p.name
                ),
            });
        }
    }

    let (a3, a4) = match composite {
        Some(c) => (
            c.name.clone(),
            dokument
                .get(&c.name)
                .cloned()
                .unwrap_or(Wert::Objekt(BTreeMap::new()))
                .schreiben(),
        ),
        None => ("@".to_string(), Wert::Objekt(dokument.clone()).schreiben()),
    };
    Pruefung { befunde, dokument, a3, a4 }
}

fn pruefe_maxcount(wert: &Wert, befunde: &mut Vec<Befund>) {
    match wert {
        Wert::Zahl(n) if *n > MAXCOUNT_OBERGRENZE => {
            befunde.push(Befund {
                regel: "P4",
                schwere: Schwere::Hinweis,
                text: format!(
                    "maxCount={} liegt ueber der Obergrenze {}; die Target-Apps liefern hoechstens {}. \
                     Der Wert wird auf {} gesetzt.",
                    n, MAXCOUNT_OBERGRENZE, MAXCOUNT_OBERGRENZE, MAXCOUNT_OBERGRENZE
                ),
            });
        }
        Wert::Zahl(0) => {
            befunde.push(Befund {
                regel: "P4",
                schwere: Schwere::Hinweis,
                text: "maxCount=0 liefert eine leere Liste - ein Treffer ist so nicht nachweisbar."
                    .to_string(),
            });
        }
        Wert::Zahl(n) if *n < 0 => {
            befunde.push(Befund {
                regel: "P4",
                schwere: Schwere::Hinweis,
                text: "maxCount=-1 heisst bei Kontakten 'alle', bei Fotos, Lesezeichen und Dateien \
                       aber Fehler 1001. Nur nach Sichtpruefung verwenden."
                    .to_string(),
            });
        }
        _ => {}
    }
}

impl Funktion {
    /// Kontakte verlangen bei `findPersons` einen nicht leeren Suchtext.
    fn shortname_verlangt_inhalt(&self) -> bool {
        self.name.eq_ignore_ascii_case("findPersons")
    }
}

/// Setzt die Hinweise um, die den Aufruf veraendern (maxCount kappen).
pub fn anwenden(dokument: &mut BTreeMap<String, Wert>) {
    for wert in dokument.values_mut() {
        if let Wert::Objekt(m) = wert {
            if let Some(Wert::Zahl(n)) = m.get("maxCount").cloned() {
                if n > MAXCOUNT_OBERGRENZE {
                    m.insert("maxCount".to_string(), Wert::Zahl(MAXCOUNT_OBERGRENZE));
                }
            }
        }
    }
}

/// Kurzfassung fuer die Anzeige: liest die Registry und prueft.
pub fn pruefen_fuer(reg: &Registry, paket: Option<&str>, schluessel: &str, eingabe_json: &str) -> Result<Pruefung, String> {
    let f = reg.finde(paket, schluessel)?;
    let wert = if eingabe_json.trim().is_empty() {
        Wert::Objekt(BTreeMap::new())
    } else {
        Wert::lesen(eingabe_json)?
    };
    let obj = match wert {
        Wert::Objekt(m) => m,
        Wert::Null => BTreeMap::new(),
        _ => return Err("die Argumente muessen ein JSON-Objekt sein".to_string()),
    };
    Ok(pruefen(f, &obj))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::Registry;

    fn obj(s: &str) -> BTreeMap<String, Wert> {
        match Wert::lesen(s).unwrap() {
            Wert::Objekt(m) => m,
            _ => panic!("kein Objekt"),
        }
    }

    fn fehler_regeln(p: &Pruefung) -> Vec<&str> {
        p.fehler().iter().map(|b| b.regel).collect()
    }

    #[test]
    fn gueltiger_aufruf_geht_durch() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = pruefen(f, &obj("{\"query\":\"e\",\"maxCount\":3}"));
        assert_eq!(fehler_regeln(&p), Vec::<&str>::new(), "{:?}", p.befunde);
        let doc = p.dokument.get("findPersonsParams").expect("Sammelparameter");
        match doc {
            Wert::Objekt(m) => {
                assert_eq!(m["query"], Wert::Text("e".into()));
                assert_eq!(m["maxCount"], Wert::Zahl(3));
                assert_eq!(m["_typ"], Wert::Text("com.google.android.appfunctions.schema.common.v1.persons.FindPersonsParams".into()));
            }
            _ => panic!("kein Objekt"),
        }
    }

    #[test]
    fn p1_pflichtfeld_fehlt() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = pruefen(f, &obj("{\"query\":\"e\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P1"]);
        assert!(p.fehler()[0].text.contains("maxCount"));
    }

    #[test]
    fn p2_unbekanntes_feld_wird_abgelehnt() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = pruefen(f, &obj("{\"query\":\"e\",\"maxCount\":3,\"max\":3}"));
        assert_eq!(fehler_regeln(&p), vec!["P2"]);
        assert!(p.fehler()[0].text.contains("max"));
    }

    #[test]
    fn p3_zahl_als_text_wird_abgelehnt() {
        // Gegenprobe zum Geraeteverhalten: die Plattform nimmt "3" an und
        // liefert eine leere Liste. Der Fehler muss hier auffallen.
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = pruefen(f, &obj("{\"query\":\"e\",\"maxCount\":\"3\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P3"]);
    }

    #[test]
    fn p4_maxcount_obergrenze_ist_hinweis_und_wird_gekappt() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let mut p = pruefen(f, &obj("{\"query\":\"e\",\"maxCount\":500}"));
        assert!(!p.hat_fehler());
        assert!(p.befunde.iter().any(|b| b.regel == "P4"));
        anwenden(&mut p.dokument);
        match &p.dokument["findPersonsParams"] {
            Wert::Objekt(m) => assert_eq!(m["maxCount"], Wert::Zahl(30)),
            _ => panic!("kein Objekt"),
        }
    }

    #[test]
    fn p5_leerer_suchtext_bei_kontakten() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let p = pruefen(f, &obj("{\"query\":\"\",\"maxCount\":3}"));
        assert_eq!(fehler_regeln(&p), vec!["P5"]);
    }

    #[test]
    fn p0_funktion_ohne_parameterangabe() {
        let r = Registry::eigene();
        // getPermissionsDeviceState hat in der Registry keinen Parameter.
        let f = r.finde(Some("com.google.android.permissioncontroller"), "getPermissionsDeviceState").unwrap();
        let p = pruefen(f, &obj("{}"));
        assert!(!p.hat_fehler());
        assert_eq!(p.befunde[0].regel, "P0");
        let p2 = pruefen(f, &obj("{\"irgendwas\":1}"));
        assert!(p2.hat_fehler());
    }

    #[test]
    fn flacher_parameter_landet_oben() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "getPersons").unwrap();
        let p = pruefen(f, &obj("{\"personIds\":[\"73\"]}"));
        assert!(!p.hat_fehler(), "{:?}", p.befunde);
        assert_eq!(p.dokument["personIds"], Wert::Liste(vec![Wert::Text("73".into())]));
    }

    #[test]
    fn liste_statt_text_ist_ein_typpfehler() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "getPersons").unwrap();
        let p = pruefen(f, &obj("{\"personIds\":\"73\"}"));
        assert_eq!(fehler_regeln(&p), vec!["P3"]);
    }
}
