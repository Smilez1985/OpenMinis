//! Die gemessene Registry. Sie wird als Datendatei mitgeliefert
//! (`data/registry.txt`, erzeugt von `werkzeuge/registry_zu_daten.py` aus
//! `appfunctions-registry.xml`) und beim Bauen ins Binary eingebettet.
//!
//! Jede Angabe traegt ihre Herkunft (`quelle`): `dump` (Laufzeit-Metadaten des
//! Geraets), `schema` (asset/app_function.xml der Ziel-APK), `aufruf-ok` (ein
//! Aufruf lief mit diesen Werten durch), `fehlermeldung` (die Ziel-App hat das
//! Feld benannt), `konvention` oder `vermutet` (geraten - wird angezeigt).

pub const REGISTRY: &str = include_str!("../data/registry.txt");

#[derive(Debug, Clone, PartialEq)]
pub struct Feld {
    pub name: String,
    pub datentyp: String,
    pub pflicht: bool,
    pub quelle: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Parameter {
    pub name: String,
    pub typ: String,
    /// `oberste-ebene`, wenn der Parameter selbst auf der obersten Ebene liegt
    /// (z. B. `personIds`), leer sonst.
    pub ebene: String,
    pub quelle: String,
    pub felder: Vec<Feld>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Funktion {
    pub paket: String,
    pub id: String,
    pub name: String,
    pub kategorie: String,
    pub status: String,
    pub geprueft: bool,
    pub parameter: Vec<Parameter>,
    pub beispiel: Option<String>,
}

impl Funktion {
    pub fn kurzname(&self) -> &str {
        self.id.rsplit('#').next().unwrap_or(&self.id)
    }

    /// Parameter, dessen Felder auf der obersten Ebene angegeben werden
    /// (genau einer in dieser Registry). Composite-Parameter tragen einen
    /// Klassennamen als Typ, flache einen sprechenden (`text`,
    /// `liste-von-text`).
    pub fn composite(&self) -> Option<&Parameter> {
        self.parameter
            .iter()
            .find(|p| p.ebene != "oberste-ebene" && !p.typ.contains("text") && p.typ.contains('.'))
    }

    pub fn flach(&self) -> Vec<&Parameter> {
        self.parameter
            .iter()
            .filter(|p| p.ebene == "oberste-ebene" || p.typ == "text" || p.typ == "liste-von-text")
            .collect()
    }

    pub fn ist_lesend(&self) -> bool {
        let n = self.kurzname().to_lowercase();
        let n = n.rsplit('_').next().unwrap_or(&n);
        ["find", "get", "search", "list", "is", "query", "read", "lookup", "fetch"]
            .iter()
            .any(|v| n.starts_with(v))
    }
}

#[derive(Debug, Clone)]
pub struct Registry {
    pub geraet: String,
    pub funktionen: Vec<Funktion>,
}

impl Registry {
    pub fn aus_text(text: &str) -> Registry {
        let mut funktionen: Vec<Funktion> = Vec::new();
        let mut geraet = String::new();
        for zeile in text.lines() {
            if zeile.starts_with('#') || zeile.trim().is_empty() {
                continue;
            }
            let t: Vec<&str> = zeile.split('\t').collect();
            match t[0] {
                "E" => {
                    if t.len() > 2 {
                        geraet = t[2].to_string();
                    }
                }
                "F" => funktionen.push(Funktion {
                    paket: feld(&t, 1),
                    id: feld(&t, 2),
                    name: feld(&t, 3),
                    kategorie: feld(&t, 4),
                    status: feld(&t, 5),
                    geprueft: feld(&t, 6) == "ja",
                    parameter: Vec::new(),
                    beispiel: None,
                }),
                "P" => {
                    if let Some(f) = funktionen.last_mut() {
                        f.parameter.push(Parameter {
                            name: feld(&t, 2),
                            typ: feld(&t, 3),
                            ebene: feld(&t, 4),
                            quelle: feld(&t, 5),
                            felder: Vec::new(),
                        });
                    }
                }
                "D" => {
                    if let Some(f) = funktionen.last_mut() {
                        // D: id, parametername, feld, datentyp, pflicht, quelle
                        let pname = feld(&t, 2);
                        if let Some(p) = f.parameter.iter_mut().rev().find(|p| p.name == pname) {
                            p.felder.push(Feld {
                                name: feld(&t, 3),
                                datentyp: feld(&t, 4),
                                pflicht: feld(&t, 5) == "ja",
                                quelle: feld(&t, 6),
                            });
                        }
                    }
                }
                "B" => {
                    if let Some(f) = funktionen.last_mut() {
                        f.beispiel = Some(format!(
                            "{} (args: {}, quelle: {})",
                            feld(&t, 4),
                            feld(&t, 2),
                            feld(&t, 3)
                        ));
                    }
                }
                _ => {}
            }
        }
        Registry { geraet, funktionen }
    }

    pub fn eigene() -> Registry {
        Registry::aus_text(REGISTRY)
    }

    /// Sucht eine Funktion. `schluessel` ist der volle Pfad, ein Teil davon
    /// oder der Kurzname. Mehrdeutigkeit ist ein Fehler mit Vorschlagsliste,
    /// damit nichts Falsches aufgerufen wird.
    pub fn finde(&self, paket: Option<&str>, schluessel: &str) -> Result<&Funktion, String> {
        let k = schluessel.to_lowercase();
        let kandidaten: Vec<&Funktion> = self
            .funktionen
            .iter()
            .filter(|f| paket.map_or(true, |p| f.paket == p))
            .filter(|f| {
                f.id.to_lowercase() == k
                    || f.id.to_lowercase().ends_with(schluessel)
                    || f.kurzname().to_lowercase() == k
                    || f.id.to_lowercase().contains(&k)
            })
            .collect();
        match kandidaten.len() {
            0 => Err(format!("keine Funktion passt auf '{}'", schluessel)),
            1 => Ok(kandidaten[0]),
            _ => {
                let genau: Vec<&&Funktion> = kandidaten
                    .iter()
                    .filter(|f| f.kurzname().to_lowercase() == k)
                    .collect();
                if genau.len() == 1 {
                    return Ok(genau[0]);
                }
                Err(format!(
                    "'{}' ist mehrdeutig:\n{}",
                    schluessel,
                    kandidaten
                        .iter()
                        .map(|f| format!("  {} ({})", f.id, f.paket))
                        .collect::<Vec<_>>()
                        .join("\n")
                ))
            }
        }
    }

    pub fn suche(&self, muster: &str) -> Vec<&Funktion> {
        let m = muster.to_lowercase();
        self.funktionen
            .iter()
            .filter(|f| {
                m.is_empty()
                    || f.id.to_lowercase().contains(&m)
                    || f.paket.to_lowercase().contains(&m)
                    || f.kategorie.to_lowercase().contains(&m)
            })
            .collect()
    }
}

fn feld(t: &[&str], i: usize) -> String {
    t.get(i).unwrap_or(&"").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_laedt_und_ist_vollstaendig() {
        let r = Registry::eigene();
        assert_eq!(r.geraet, "testgeraet-android16");
        assert_eq!(r.funktionen.len(), 61, "61 Funktionen erwartet");
        let geprueft = r.funktionen.iter().filter(|f| f.geprueft).count();
        assert_eq!(geprueft, 13, "13 bestaetigte Aufrufe erwartet");
    }

    #[test]
    fn felder_haengen_am_rechten_parameter() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "findPersons").unwrap();
        let c = f.composite().expect("findPersonsParams erwartet");
        assert_eq!(c.name, "findPersonsParams");
        assert!(c.felder.iter().any(|x| x.name == "maxCount" && x.pflicht));
        assert!(c.felder.iter().any(|x| x.name == "query" && !x.pflicht));
    }

    #[test]
    fn personen_ids_liegen_oberste_ebene() {
        let r = Registry::eigene();
        let f = r.finde(Some("com.samsung.android.app.contacts"), "getPersons").unwrap();
        let flach = f.flach();
        assert_eq!(flach.len(), 1);
        assert_eq!(flach[0].name, "personIds");
        assert!(f.composite().is_none());
    }

    #[test]
    fn mehrdeutiger_kurzname_ist_fehler() {
        // getPersons gibt es bei Kontakten und beim Telefon.
        let r = Registry::eigene();
        assert!(r.finde(None, "getPersons").is_err());
        assert!(r.finde(Some("com.samsung.android.dialer"), "getPersons").is_ok());
    }

    #[test]
    fn lesend_und_schreibend() {
        let r = Registry::eigene();
        assert!(r.finde(None, "findBookmarks").unwrap().ist_lesend());
        assert!(r.finde(None, "getHistories").unwrap().ist_lesend());
        assert!(!r.finde(None, "createAlbum").unwrap().ist_lesend());
        assert!(!r.finde(None, "showSite").unwrap().ist_lesend());
    }
}
