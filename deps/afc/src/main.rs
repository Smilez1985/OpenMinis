//! afc — AppFunctions aufrufen: Registry, Pruefung, Fehleruebersetzung, Binder.
//!
//! Gebaut aus den Messungen vom 29./30.10.2026 auf einem Testgeraet (Android 16).
//! Die Registry liegt in `data/registry.txt` und ist eingebettet; jede Angabe
//! traegt ihre Herkunft.
//!
//! Unterkommandos:
//!   list  [muster] [--alles] [--json]        Funktionen zeigen
//!   show  <name|id>                          eine Funktion mit Parametern
//!   check <paket|-> <name> [--args JSON]     nur pruefen, nichts aufrufen
//!   call  <paket|-> <name> [--args JSON] [--schreiben] [--trocken] [--zeit N]
//!   proben                                   Proben fuer den Geraetetest (TSV: Paket, Name, Args, bestaetigt)
//!   codes                                    Fehlercodes erklaeren
//!   refresh [--dump DATEI] [--nach DATEI]    Registry dieses Geraets aus dumpsys erneuern
//!
//! Schreibende Funktionen laufen nur mit `--schreiben`. Die Pruefung ist die
//! Zusage, nicht der Dialog: was die Registry nicht hergibt, wird als offen
//! gemeldet statt geraten.

mod auffrischen;
mod fehler;
mod json;
mod proben;
mod pruefen;
mod registry;
mod weg;

use json::Wert;
use registry::Registry;
use std::collections::BTreeMap;

const HILFE: &str = "\
afc 0.1.0 — AppFunctions aufrufen (Binder-Weg, Aufrufer uid 2000)

  afc list [muster] [--alles] [--json]        Funktionen aus der Registry
  afc show <name|id>                          eine Funktion mit Parametern
  afc check <paket|-> <name> [--args JSON]    pruefen, nicht aufrufen
  afc call  <paket|-> <name> [--args JSON] [--schreiben] [--trocken] [--zeit N]
  afc codes                                   Fehlercodes und ihre Bedeutung
  afc proben                                  Proben des Geraetetests (aus der Registry)
  afc refresh [--dump DATEI] [--nach DATEI]   Registry aus dumpsys app_function erneuern

  --alles      auch Funktionen, die aus sind
  --schreiben  schreibende Funktion wirklich aufrufen (sonst nur Pruefung)
  --trocken    Aufruf vorbereiten, aber nicht ausfuehren
  --registry DATEI  Registry statt der eingebetteten ($AFC_REGISTRY; sonst
               /var/minis/shared/afc/registry.txt, wenn vorhanden)
  --jar PFAD   aus.jar (Vorrang vor $AFC_JAR; Vorgabe /var/minis/shared/afc/aus.jar)
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let r = registry_waehlen(&args);
    let args = ohne_option(args, "--registry");
    let ergebnis = match args.first().map(|s| s.as_str()) {
        None | Some("--hilfe") | Some("-h") | Some("help") => {
            println!("{}", HILFE);
            Ok(())
        }
        Some("list") => liste(&r, &args[1..]),
        Some("show") => zeige(&r, &args[1..]),
        Some("check") => pruefe(&r, &args[1..], false),
        Some("call") => pruefe(&r, &args[1..], true),
        Some("refresh") => frische(&args[1..]),
        Some("proben") => {
            for p in proben::proben(&r) {
                println!("{}\t{}\t{}\t{}", p.paket, p.name, p.args, if p.bestaetigt { "ja" } else { "nein" });
            }
            Ok(())
        }
        Some("codes") => {
            for (c, t) in fehler::CODES {
                println!("{:18} {}", c, t);
            }
            Ok(())
        }
        Some(anderes) => Err(format!("unbekanntes Unterkommando '{}'\n\n{}", anderes, HILFE)),
    };
    if let Err(e) = ergebnis {
        eprintln!("afc: {}", e);
        std::process::exit(2);
    }
}

fn liste(r: &Registry, args: &[String]) -> Result<(), String> {
    let alles = args.iter().any(|a| a == "--alles");
    let als_json = args.iter().any(|a| a == "--json");
    let muster = args
        .iter()
        .find(|a| !a.starts_with("--"))
        .map(|s| s.as_str())
        .unwrap_or("");
    let treffer = r.suche(muster);
    if als_json {
        let mut s = String::from("[");
        for (i, f) in treffer.iter().enumerate() {
            if i > 0 {
                s.push(',');
            }
            s.push_str(&format!(
                "{{\"id\":{},\"paket\":{},\"name\":{},\"kategorie\":{},\"status\":{},\"geprueft\":{}}}",
                json::Wert::Text(f.id.clone()).schreiben(),
                json::Wert::Text(f.paket.clone()).schreiben(),
                json::Wert::Text(f.name.clone()).schreiben(),
                json::Wert::Text(f.kategorie.clone()).schreiben(),
                json::Wert::Text(f.status.clone()).schreiben(),
                f.geprueft
            ));
        }
        s.push(']');
        println!("{}", s);
        return Ok(());
    }
    let mut letztes_paket = String::new();
    let mut gezeigt = 0;
    for f in treffer {
        if f.status == "aus" && !alles {
            continue;
        }
        if f.paket != letztes_paket {
            println!("\n{}", f.paket);
            letztes_paket = f.paket.clone();
        }
        println!(
            "  {:<28} {:<14} {}{}",
            f.kurzname(),
            if f.kategorie.is_empty() { "-" } else { &f.kategorie },
            if f.ist_lesend() { "liest " } else { "SCHREIBT" },
            if f.geprueft { "  (gemessen)" } else { "" }
        );
        gezeigt += 1;
    }
    println!(
        "\n{} Funktionen gezeigt (von {} in der Registry{}).",
        gezeigt,
        r.funktionen.len(),
        if alles { "" } else { "; ausgeschaltete mit --alles" }
    );
    Ok(())
}

fn zeige(r: &Registry, args: &[String]) -> Result<(), String> {
    let name = args.first().ok_or("afc show <name|id>")?;
    let f = r.finde(None, name)?;
    println!("{}", f.id);
    println!("  Paket:      {}", f.paket);
    println!("  Kurzname:   {}", f.kurzname());
    println!(
        "  Status:     {}{}",
        f.status,
        if f.geprueft { "  (auf diesem Geraet gemessen)" } else { "" }
    );
    if !f.kategorie.is_empty() {
        println!("  Kategorie:  {}", f.kategorie);
    }
    println!(
        "  Art:        {}",
        if f.ist_lesend() { "liest (ohne Rueckfrage aufrufbar)" } else { "SCHREIBT (nur mit --schreiben)" }
    );
    if f.parameter.is_empty() {
        println!("  Parameter:  keine Angabe in der Registry");
    } else {
        println!("  Parameter:");
        for p in &f.parameter {
            println!(
                "    {} ({}, {}, ebene={}, quelle={})",
                p.name,
                p.typ,
                if p.felder.is_empty() { "Felder unbekannt" } else { "" },
                if p.ebene.is_empty() { "im Sammelparameter" } else { &p.ebene },
                p.quelle
            );
            for fd in &p.felder {
                println!(
                    "      {:<18} {:<10} {:<6} quelle={}",
                    fd.name,
                    fd.datentyp,
                    if fd.pflicht { "Pflicht" } else { "-" },
                    fd.quelle
                );
            }
        }
        if let Some(b) = &f.beispiel {
            println!("  Beispiel:   {}", b);
        }
    }
    Ok(())
}

fn arg_wert(args: &[String], name: &str) -> Option<String> {
    let i = args.iter().position(|a| a == name)?;
    args.get(i + 1).cloned()
}

fn pruefe(r: &Registry, args: &[String], aufrufen: bool) -> Result<(), String> {
    let paket = args.first().ok_or("afc check/call <paket|-> <name> [--args JSON]")?;
    let name = args.get(1).ok_or("afc check/call <paket|-> <name> [--args JSON]")?;
    let paket = if paket == "-" { None } else { Some(paket.as_str()) };
    let f = r.finde(paket, name)?;
    let args_json = arg_wert(args, "--args").unwrap_or_default();
    let schreiben_erlaubt = args.iter().any(|a| a == "--schreiben");
    let trocken = args.iter().any(|a| a == "--trocken");
    let jar = jar_waehlen(arg_wert(args, "--jar"), std::env::var("AFC_JAR").ok());

    let p = pruefen::pruefen_fuer(r, paket, name, &args_json)?;
    println!("{} ({})", f.kurzname(), f.id);
    for b in &p.befunde {
        println!(
            "  {} {}: {}",
            match b.schwere {
                pruefen::Schwere::Fehler => "FEHLER",
                pruefen::Schwere::Hinweis => "Hinweis",
            },
            b.regel,
            b.text
        );
    }
    if p.hat_fehler() {
        println!("\nKein Aufruf: {} Regelverstoesse.", p.fehler().len());
        return Err("Pruefung nicht bestanden".to_string());
    }
    let mut dokument = p.dokument.clone();
    pruefen::anwenden(&mut dokument);
    let doc = Wert::Objekt(dokument).schreiben();
    println!("  Dokument:  {}", doc);
    println!("  Aufruf:    a3={} a4={}", p.a3, p.a4);

    if !f.ist_lesend() && !schreiben_erlaubt {
        println!(
            "\nNEEDS_CONFIRMATION: '{}' aendert etwas. Erst nach Rueckfrage aufrufen, \
             dann mit --schreiben wiederholen.",
            f.kurzname()
        );
        return Ok(());
    }
    if !aufrufen {
        return Ok(());
    }
    let a = weg::Aufruf {
        paket: &f.paket,
        funktion: &f.id,
        parameter: p.a3.clone(),
        nutzlast_json: p.a4.clone(),
        jar: &jar,
        aufrufer: weg::AUFRUFER,
        zeit_s: arg_wert(args, "--zeit")
            .and_then(|z| z.parse().ok())
            .unwrap_or(30),
        trocken,
    };
    if trocken {
        println!("\n{}", weg::skript(&a)?);
        return Ok(());
    }
    let roh = weg::ausfuehren(&a)?;
    let antwort = fehler::Antwort::deute(&roh);
    println!("\n{}", fehler::bericht(&antwort));
    if antwort.ist_erfolg() {
        Ok(())
    } else {
        Err("Aufruf ohne Erfolg".to_string())
    }
}

/// Nur fuer die Anzeige: Dokument in der Form, die `Ausfuehren` erwartet.
#[allow(dead_code)]
fn als_objekt(m: BTreeMap<String, Wert>) -> Wert {
    Wert::Objekt(m)
}

/// Vorgabe fuer den Ort der `aus.jar`, wenn weder `--jar` noch `AFC_JAR` gesetzt ist.
const JAR_VORGABE: &str = "/var/minis/shared/afc/aus.jar";

/// Reihenfolge: `--jar`, dann `AFC_JAR`, dann die Vorgabe. Leere Werte zaehlen nicht.
fn jar_waehlen(arg: Option<String>, umgebung: Option<String>) -> String {
    arg.filter(|s| !s.is_empty())
        .or_else(|| umgebung.filter(|s| !s.is_empty()))
        .unwrap_or_else(|| JAR_VORGABE.to_string())
}

#[cfg(test)]
mod tests_jar {
    use super::*;

    #[test]
    fn argument_schlaegt_umgebung_schlaegt_vorgabe() {
        let a = Some("/var/minis/a.jar".to_string());
        let u = Some("/var/minis/u.jar".to_string());
        assert_eq!(jar_waehlen(a.clone(), u.clone()), "/var/minis/a.jar");
        assert_eq!(jar_waehlen(None, u), "/var/minis/u.jar");
        assert_eq!(jar_waehlen(None, None), JAR_VORGABE);
        assert_eq!(jar_waehlen(Some(String::new()), None), JAR_VORGABE);
        assert_eq!(jar_waehlen(a, Some(String::new())), "/var/minis/a.jar");
    }
}

/// Reihenfolge: `--registry`, `$AFC_REGISTRY`, `/var/minis/shared/afc/registry.txt`
/// (nur wenn vorhanden), sonst die eingebettete. Eine genannte, aber
/// unlesbare Datei ist ein Fehler, kein stiller Rueckfall.
fn registry_waehlen(args: &[String]) -> Registry {
    let genannt = arg_wert(args, "--registry")
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("AFC_REGISTRY").ok().filter(|s| !s.is_empty()));
    let pfad = match &genannt {
        Some(p) => Some(p.clone()),
        None if std::path::Path::new(REGISTRY_VORGABE).exists() => Some(REGISTRY_VORGABE.to_string()),
        None => None,
    };
    match pfad {
        Some(p) => match std::fs::read_to_string(&p) {
            Ok(t) => Registry::aus_text(&t),
            Err(e) => {
                eprintln!("afc: Registry {} nicht lesbar: {}", p, e);
                std::process::exit(2);
            }
        },
        None => Registry::eigene(),
    }
}

const REGISTRY_VORGABE: &str = "/var/minis/shared/afc/registry.txt";

fn frische(args: &[String]) -> Result<(), String> {
    let dump = match arg_wert(args, "--dump") {
        Some(p) => std::fs::read_to_string(&p).map_err(|e| format!("{}: {}", p, e))?,
        None => weg::shell("dumpsys app_function")?,
    };
    if !dump.contains("AppFunctionMetadata for:") {
        return Err("der Dump enthaelt keine AppFunctionMetadata - nichts geschrieben".to_string());
    }
    let nach = arg_wert(args, "--nach").unwrap_or_else(|| REGISTRY_VORGABE.to_string());
    let e = auffrischen::auffrischen(&dump, registry::REGISTRY, "aufgefrischt");
    // Sidecar + atomares mv, damit nie eine halbe Datei liegt.
    let tmp = format!("{}.neu", nach);
    std::fs::write(&tmp, &e.text).map_err(|x| format!("{}: {}", tmp, x))?;
    std::fs::rename(&tmp, &nach).map_err(|x| format!("{}: {}", nach, x))?;
    let r = Registry::aus_text(&e.text);
    println!("Registry geschrieben: {}", nach);
    println!("  {} Funktionen im Dump, davon {} mit Wissen aus der eingebetteten Registry, {} neu (nur Dump-Angaben, Felder unbekannt).", r.funktionen.len(), e.behalten, e.neu.len());
    if !e.fehlen.is_empty() {
        println!("  {} eingebettete Funktionen fehlen auf diesem Geraet (nicht uebernommen).", e.fehlen.len());
    }
    println!("  geprueft: 0 (bestaetigt wurde nur auf dem Messgeraet).");
    Ok(())
}

/// Entfernt `name WERT` aus den Argumenten, damit der Wert nicht als
/// Suchmuster oder Unterkommando gelesen wird.
fn ohne_option(args: Vec<String>, name: &str) -> Vec<String> {
    let mut aus = Vec::new();
    let mut i = 0;
    while i < args.len() {
        if args[i] == name {
            i += 2;
        } else {
            aus.push(args[i].clone());
            i += 1;
        }
    }
    aus
}

#[cfg(test)]
mod tests_option {
    use super::ohne_option;
    #[test]
    fn option_samt_wert_faellt_weg() {
        let a: Vec<String> = ["--registry", "/x", "list", "kontakt"].iter().map(|s| s.to_string()).collect();
        assert_eq!(ohne_option(a, "--registry"), vec!["list", "kontakt"]);
    }
}
