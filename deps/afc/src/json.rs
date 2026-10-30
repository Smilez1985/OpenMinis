//! Kleiner JSON-Leser und -Schreiber. Kein crates.io im Sandkasten, also selbst
//! gebaut. Es wird nur gebraucht, was AppFunctions braucht: Objekt, Liste,
//! Text, Ganzzahl, Kommazahl, Wahrheitswert, null.
//!
//! Wichtig: Ganzzahl und Kommazahl werden getrennt gehalten. Die Plattform
//! unterscheidet `long` und `double`, und eine Zahl als Text ("3") nimmt sie
//! stillschweigend an und liefert dann eine leere Liste - genau das soll die
//! Pruefung verhindern, also darf der Leser hier nichts glattziehen.

use std::collections::BTreeMap;
use std::fmt::Write as _;

#[derive(Debug, Clone, PartialEq)]
pub enum Wert {
    Null,
    Wahr(bool),
    Zahl(i64),
    Komma(f64),
    Text(String),
    Liste(Vec<Wert>),
    Objekt(BTreeMap<String, Wert>),
}

impl Wert {
    pub fn als_text(&self) -> Option<&str> {
        match self {
            Wert::Text(s) => Some(s),
            _ => None,
        }
    }

    pub fn art_name(&self) -> &'static str {
        match self {
            Wert::Null => "null",
            Wert::Wahr(_) => "boolean",
            Wert::Zahl(_) => "ganze Zahl",
            Wert::Komma(_) => "Kommazahl",
            Wert::Text(_) => "Text",
            Wert::Liste(_) => "Liste",
            Wert::Objekt(_) => "Objekt",
        }
    }

    /// Datentyp-Name, wie ihn die Registry schreibt (`long`, `string`, ...).
    pub fn datentyp(&self) -> &'static str {
        match self {
            Wert::Null => "null",
            Wert::Wahr(_) => "boolean",
            Wert::Zahl(_) => "long",
            Wert::Komma(_) => "double",
            Wert::Text(_) => "string",
            Wert::Liste(_) => "liste",
            Wert::Objekt(_) => "document",
        }
    }

    /// Vergleicht den Datentyp der Registry mit dem tatsaechlichen Wert.
    /// `document` und `liste` werden nicht bis in die Tiefe geprueft: die
    /// inneren Feldnamen sind auf diesem Geraet nicht messbar (siehe
    /// SAMSUNG-PARAMETERS.md), eine Pruefung dort waere geraten.
    pub fn passt_zu(&self, datentyp: &str) -> bool {
        match datentyp {
            "long" => matches!(self, Wert::Zahl(_)),
            "double" => matches!(self, Wert::Komma(_) | Wert::Zahl(_)),
            "boolean" => matches!(self, Wert::Wahr(_)),
            "string" => matches!(self, Wert::Text(_)),
            "liste-von-text" => match self {
                Wert::Liste(l) => l.iter().all(|x| matches!(x, Wert::Text(_))),
                _ => false,
            },
            "document" => matches!(self, Wert::Objekt(_)),
            "" => true,
            _ => true, // unbekannter Typ der Registry: nicht dagegen pruefen
        }
    }

    pub fn schreiben(&self) -> String {
        let mut s = String::new();
        self.schreibe_in(&mut s);
        s
    }

    fn schreibe_in(&self, s: &mut String) {
        match self {
            Wert::Null => s.push_str("null"),
            Wert::Wahr(b) => s.push_str(if *b { "true" } else { "false" }),
            Wert::Zahl(z) => {
                let _ = write!(s, "{}", z);
            }
            Wert::Komma(k) => {
                if k.fract() == 0.0 && k.abs() < 1e15 {
                    let _ = write!(s, "{:.1}", k);
                } else {
                    let _ = write!(s, "{}", k);
                }
            }
            Wert::Text(t) => text_schreiben(t, s),
            Wert::Liste(l) => {
                s.push('[');
                for (i, x) in l.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    x.schreibe_in(s);
                }
                s.push(']');
            }
            Wert::Objekt(o) => {
                s.push('{');
                for (i, (k, v)) in o.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    text_schreiben(k, s);
                    s.push(':');
                    v.schreibe_in(s);
                }
                s.push('}');
            }
        }
    }

    /// Liest einen Text als JSON-Wert. Ein blosses Wort wird nicht akzeptiert.
    pub fn lesen(text: &str) -> Result<Wert, String> {
        let mut l = Leser { z: text.as_bytes(), i: 0 };
        l.leerraum();
        let w = l.wert()?;
        l.leerraum();
        if l.i != l.z.len() {
            return Err(format!("Ueberschuss ab Zeichen {}", l.i + 1));
        }
        Ok(w)
    }
}

fn text_schreiben(t: &str, s: &mut String) {
    s.push('"');
    for c in t.chars() {
        match c {
            '"' => s.push_str("\\\""),
            '\\' => s.push_str("\\\\"),
            '\n' => s.push_str("\\n"),
            '\r' => s.push_str("\\r"),
            '\t' => s.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(s, "\\u{:04x}", c as u32);
            }
            c => s.push(c),
        }
    }
    s.push('"');
}

struct Leser<'a> {
    z: &'a [u8],
    i: usize,
}

impl<'a> Leser<'a> {
    fn leerraum(&mut self) {
        while self.i < self.z.len() && (self.z[self.i] as char).is_whitespace() {
            self.i += 1;
        }
    }

    fn blick(&self) -> Option<u8> {
        self.z.get(self.i).copied()
    }

    fn erwarte(&mut self, c: u8) -> Result<(), String> {
        if self.blick() == Some(c) {
            self.i += 1;
            Ok(())
        } else {
            Err(format!(
                "erwartet '{}' an Zeichen {}",
                c as char,
                self.i + 1
            ))
        }
    }

    fn wert(&mut self) -> Result<Wert, String> {
        self.leerraum();
        match self.blick() {
            None => Err("unerwartetes Ende".to_string()),
            Some(b'{') => {
                self.i += 1;
                let mut m = BTreeMap::new();
                self.leerraum();
                if self.blick() == Some(b'}') {
                    self.i += 1;
                    return Ok(Wert::Objekt(m));
                }
                loop {
                    self.leerraum();
                    let k = self.text()?;
                    self.leerraum();
                    self.erwarte(b':')?;
                    let v = self.wert()?;
                    m.insert(k, v);
                    self.leerraum();
                    match self.blick() {
                        Some(b',') => {
                            self.i += 1;
                        }
                        Some(b'}') => {
                            self.i += 1;
                            return Ok(Wert::Objekt(m));
                        }
                        _ => return Err(format!("',' oder '}}' erwartet an Zeichen {}", self.i + 1)),
                    }
                }
            }
            Some(b'[') => {
                self.i += 1;
                let mut l = Vec::new();
                self.leerraum();
                if self.blick() == Some(b']') {
                    self.i += 1;
                    return Ok(Wert::Liste(l));
                }
                loop {
                    l.push(self.wert()?);
                    self.leerraum();
                    match self.blick() {
                        Some(b',') => {
                            self.i += 1;
                        }
                        Some(b']') => {
                            self.i += 1;
                            return Ok(Wert::Liste(l));
                        }
                        _ => return Err(format!("',' oder ']' erwartet an Zeichen {}", self.i + 1)),
                    }
                }
            }
            Some(b'"') => Ok(Wert::Text(self.text()?)),
            Some(b't') => self.wort("true", Wert::Wahr(true)),
            Some(b'f') => self.wort("false", Wert::Wahr(false)),
            Some(b'n') => self.wort("null", Wert::Null),
            Some(_) => self.zahl(),
        }
    }

    fn wort(&mut self, w: &str, v: Wert) -> Result<Wert, String> {
        if self.z[self.i..].starts_with(w.as_bytes()) {
            self.i += w.len();
            Ok(v)
        } else {
            Err(format!("unbekanntes Wort an Zeichen {}", self.i + 1))
        }
    }

    fn zahl(&mut self) -> Result<Wert, String> {
        let anfang = self.i;
        if self.blick() == Some(b'-') {
            self.i += 1;
        }
        let mut komma = false;
        while let Some(c) = self.blick() {
            match c {
                b'0'..=b'9' => self.i += 1,
                b'.' | b'e' | b'E' | b'+' | b'-' => {
                    if c == b'.' {
                        komma = true;
                    }
                    self.i += 1;
                }
                _ => break,
            }
        }
        let roh = std::str::from_utf8(&self.z[anfang..self.i]).unwrap_or("");
        if roh.is_empty() {
            return Err(format!("keine Zahl an Zeichen {}", anfang + 1));
        }
        if komma {
            roh.parse::<f64>()
                .map(Wert::Komma)
                .map_err(|e| format!("Kommazahl '{}' unlesbar: {}", roh, e))
        } else {
            match roh.parse::<i64>() {
                Ok(z) => Ok(Wert::Zahl(z)),
                Err(_) => roh
                    .parse::<f64>()
                    .map(Wert::Komma)
                    .map_err(|e| format!("Zahl '{}' unlesbar: {}", roh, e)),
            }
        }
    }

    fn text(&mut self) -> Result<String, String> {
        self.erwarte(b'"')?;
        let mut s = String::new();
        loop {
            let c = self
                .blick()
                .ok_or_else(|| "Text ohne Ende".to_string())?;
            self.i += 1;
            match c {
                b'"' => return Ok(s),
                b'\\' => {
                    let d = self
                        .blick()
                        .ok_or_else(|| "Fluchtzeichen ohne Ende".to_string())?;
                    self.i += 1;
                    match d {
                        b'n' => s.push('\n'),
                        b't' => s.push('\t'),
                        b'r' => s.push('\r'),
                        b'b' => s.push('\u{8}'),
                        b'f' => s.push('\u{c}'),
                        b'u' => {
                            if self.i + 4 > self.z.len() {
                                return Err("\\u ohne vier Ziffern".to_string());
                            }
                            let h = std::str::from_utf8(&self.z[self.i..self.i + 4])
                                .map_err(|_| "\\u ist kein UTF-8".to_string())?;
                            let n = u32::from_str_radix(h, 16)
                                .map_err(|_| format!("\\u{} ist keine Hexzahl", h))?;
                            self.i += 4;
                            s.push(char::from_u32(n).unwrap_or('\u{fffd}'));
                        }
                        d => s.push(d as char),
                    }
                }
                _ => {
                    // UTF-8 durchreichen
                    let start = self.i - 1;
                    let len = utf8_laenge(c);
                    self.i = start + len;
                    let st = std::str::from_utf8(&self.z[start..self.i])
                        .map_err(|_| "Text ist kein UTF-8".to_string())?;
                    s.push_str(st);
                }
            }
        }
    }
}

fn utf8_laenge(erstes: u8) -> usize {
    if erstes < 0x80 {
        1
    } else if erstes >> 5 == 0b110 {
        2
    } else if erstes >> 4 == 0b1110 {
        3
    } else {
        4
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zahl_bleibt_zahl() {
        let w = Wert::lesen("{\"maxCount\":3}").unwrap();
        match w {
            Wert::Objekt(m) => assert_eq!(m["maxCount"], Wert::Zahl(3)),
            _ => panic!("kein Objekt"),
        }
    }

    #[test]
    fn zahl_als_text_bleibt_text() {
        // Gegenprobe: die Plattform nimmt "3" stillschweigend an und liefert
        // eine leere Liste. Der Leser darf das nicht glattziehen.
        let w = Wert::lesen("{\"maxCount\":\"3\"}").unwrap();
        match w {
            Wert::Objekt(m) => assert_eq!(m["maxCount"], Wert::Text("3".to_string())),
            _ => panic!("kein Objekt"),
        }
    }

    #[test]
    fn umlaut_und_liste() {
        let w = Wert::lesen("{\"q\":\"Straße\",\"ids\":[\"73\",\"74\"]}").unwrap();
        let s = w.schreiben();
        assert_eq!(Wert::lesen(&s).unwrap(), w);
    }

    #[test]
    fn ueberschuss_ist_fehler() {
        assert!(Wert::lesen("{\"a\":1} x").is_err());
    }
}
