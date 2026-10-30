//! afc — AppFunctions aufrufen: Registry, Pruefung, Fehleruebersetzung, Binder.
//!
//! Gebaut aus den Messungen vom 29./30.10.2026 auf s20fe (UN1CA, Android 16).
//! Die Registry liegt in `data/registry.txt` und ist eingebettet; jede Angabe
//! traegt ihre Herkunft.
//!
//! Unterkommandos:
//!   list  [muster] [--alles] [--json]        Funktionen zeigen
//!   show  <name|id>                          eine Funktion mit Parametern
//!   check <paket|-> <name> [--args JSON]     nur pruefen, nichts aufrufen
//!   call  <paket|-> <name> [--args JSON] [--schreiben] [--trocken] [--zeit N]
//!   codes                                    Fehlercodes erklaeren
//!
//! Schreibende Funktionen laufen nur mit `--schreiben`. Die Pruefung ist die
//! Zusage, nicht der Dialog: was die Registry nicht hergibt, wird als offen
//! gemeldet statt geraten.

mod fehler;
mod json;
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

  --alles      auch Funktionen, die aus sind
  --schreiben  schreibende Funktion wirklich aufrufen (sonst nur Pruefung)
  --trocken    Aufruf vorbereiten, aber nicht ausfuehren
  --jar PFAD   aus.jar (Vorgabe /var/minis/shared/appfunktionen-bau/aus.jar)
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let r = Registry::eigene();
    let ergebnis = match args.first().map(|s| s.as_str()) {
        None | Some("--hilfe") | Some("-h") | Some("help") => {
            println!("{}", HILFE);
            Ok(())
        }
        Some("list") => liste(&r, &args[1..]),
        Some("show") => zeige(&r, &args[1..]),
        Some("check") => pruefe(&r, &args[1..], false),
        Some("call") => pruefe(&r, &args[1..], true),
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
    let jar = arg_wert(args, "--jar")
        .unwrap_or_else(|| "/var/minis/shared/appfunktionen-bau/aus.jar".to_string());

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
