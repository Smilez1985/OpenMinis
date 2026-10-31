//! Proben fuer den Geraetetest, aus der Registry DIESES Geraets abgeleitet.
//!
//! Regeln:
//! * nur lesende Funktionen, nur eingeschaltete (`an`, `standard-an`);
//! * der Sammelparameter darf als Pflichtfelder nur `query` (string) und
//!   `maxCount` (long) haben - alles andere braucht Werte, die wir nicht
//!   kennen, und wird nicht geraten;
//! * ein Sammelparameter ohne bekannte Felder zaehlt nur, wenn die Registry
//!   fuer die Funktion einen bestaetigten Aufruf mit leerem Dokument kennt
//!   (`aufruf-ok`, Argumente `-`);
//! * Funktionen mit Parametern auf der obersten Ebene (z. B. `personIds`)
//!   brauchen echte Kennungen und fallen weg.
//!
//! Welche Apps es gibt, entscheidet damit das Geraet, nicht eine feste Liste.

use crate::registry::{Funktion, Registry};

pub struct Probe {
    pub paket: String,
    pub name: String,
    pub args: String,
    /// `true`: genau diese Form lief auf dem Messgeraet (eingebettete Registry,
    /// `geprueft`, Beispiel nicht als `callj`). `false`: aus der Form abgeleitet,
    /// ein Fehler ist dann eine Abweichung, kein Beweis gegen den Weg.
    pub bestaetigt: bool,
}

pub fn proben(r: &Registry) -> Vec<Probe> {
    let mess = Registry::eigene();
    let mut aus = Vec::new();
    for f in &r.funktionen {
        if let Some(a) = args_fuer(f) {
            let bestaetigt = mess
                .funktionen
                .iter()
                .any(|m| m.id == f.id && m.geprueft && !m.beispiel_args.starts_with("callj"));
            aus.push(Probe { paket: f.paket.clone(), name: f.kurzname().to_string(), args: a, bestaetigt });
        }
    }
    aus
}

fn args_fuer(f: &Funktion) -> Option<String> {
    if !f.ist_lesend() || !(f.status == "an" || f.status == "standard-an") {
        return None;
    }
    if f.parameter.is_empty() || !f.flach().is_empty() {
        return None;
    }
    let c = f.composite()?;
    if c.felder.is_empty() {
        let leer_bestaetigt = f.beispiel_args == "-" && f.beispiel_quelle == "aufruf-ok";
        return if leer_bestaetigt { Some("{}".to_string()) } else { None };
    }
    let mut teile = Vec::new();
    for fd in &c.felder {
        match (fd.name.as_str(), fd.datentyp.as_str()) {
            ("query", "string") => teile.push("\"query\":\"a\"".to_string()),
            ("maxCount", "long") => teile.push("\"maxCount\":3".to_string()),
            _ if fd.pflicht => return None,
            _ => {}
        }
    }
    Some(format!("{{{}}}", teile.join(",")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(text: &str) -> Registry {
        Registry::aus_text(text)
    }

    const KOPF: &str = "F\tp\tp#findX\tfindX\tk\tan\tnein\n";

    #[test]
    fn query_und_maxcount_werden_gefuellt() {
        let t = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\tmaxCount\tlong\tja\tschema\nD\tp#findX\tfParams\tquery\tstring\tnein\tschema\n", KOPF);
        let p = proben(&r(&t));
        assert_eq!(p.len(), 1);
        assert_eq!(p[0].args, "{\"maxCount\":3,\"query\":\"a\"}");
    }

    #[test]
    fn fremdes_pflichtfeld_faellt_weg() {
        let t = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\ttimeZone\tstring\tja\tschema\n", KOPF);
        assert!(proben(&r(&t)).is_empty());
    }

    #[test]
    fn unbekannte_felder_nur_mit_bestaetigtem_leeren_aufruf() {
        let ohne = format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\n", KOPF);
        assert!(proben(&r(&ohne)).is_empty(), "ohne Beleg wird nichts geraten");
        let mit = format!("{}B\tp#findX\t-\taufruf-ok\tx\n", format!("{}P\tp#findX\tfParams\ta.b.FParams\t\tdump\n", KOPF));
        assert_eq!(proben(&r(&mit))[0].args, "{}");
    }

    #[test]
    fn schreibend_ausgeschaltet_und_oberste_ebene_fallen_weg() {
        let schreibt = "F\tp\tp#setX\tsetX\tk\tan\tnein\nP\tp#setX\txParams\ta.b.XParams\t\tdump\nD\tp#setX\txParams\tmaxCount\tlong\tnein\tschema\n";
        assert!(proben(&r(schreibt)).is_empty());
        let aus = "F\tp\tp#findX\tfindX\tk\taus\tnein\nP\tp#findX\tfParams\ta.b.FParams\t\tdump\nD\tp#findX\tfParams\tmaxCount\tlong\tja\tschema\n";
        assert!(proben(&r(aus)).is_empty());
        let flach = "F\tp\tp#getX\tgetX\tk\tan\tnein\nP\tp#getX\tids\tliste-von-text\toberste-ebene\tvermutet\n";
        assert!(proben(&r(flach)).is_empty());
    }

    #[test]
    fn bestaetigt_heisst_gemessen_in_dieser_form() {
        let p = proben(&Registry::eigene());
        let von = |n: &str| p.iter().find(|x| x.name.ends_with(n)).unwrap();
        assert!(von("getDeviceStateMetadata").bestaetigt);
        assert!(von("PersonFunctions_findPersons").bestaetigt);
        assert!(!von("getHistories").bestaetigt, "gemessen mit anderen Argumenten (callj)");
        assert!(!von("findTabs").bestaetigt, "nie bestaetigt, 1001 bei maxCount allein");
    }

    #[test]
    fn eingebettete_registry_gibt_proben_ohne_samsung_zwang() {
        let p = proben(&Registry::eigene());
        let namen: Vec<&str> = p.iter().map(|x| x.name.as_str()).collect();
        assert!(namen.contains(&"getDeviceStateMetadata"), "{:?}", namen);
        assert!(namen.contains(&"getUncategorizedDeviceState"), "{:?}", namen);
        assert!(namen.iter().any(|n| n.ends_with("findPersons")), "{:?}", namen);
        assert!(p.iter().all(|x| x.args.starts_with('{')));
    }
}
