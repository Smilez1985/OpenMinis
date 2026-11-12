//! Small JSON reader and writer. There is no crates.io access in the sandbox,
//! so it is hand-written. It only covers what AppFunctions needs: object,
//! list, text, integer, floating point number, boolean, null.
//!
//! Important: integers and floating point numbers are kept apart. The platform
//! distinguishes `long` and `double`, and it silently accepts a number given as
//! text ("3") and then returns an empty list - exactly what the validation is
//! meant to catch, so the reader must not smooth anything over.

use std::collections::BTreeMap;
use std::fmt::Write as _;

#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    Float(f64),
    Text(String),
    List(Vec<Value>),
    Object(BTreeMap<String, Value>),
}

impl Value {
    pub fn as_text(&self) -> Option<&str> {
        match self {
            Value::Text(s) => Some(s),
            _ => None,
        }
    }

    pub fn kind_name(&self) -> &'static str {
        match self {
            Value::Null => "null",
            Value::Bool(_) => "boolean",
            Value::Int(_) => "integer",
            Value::Float(_) => "floating point number",
            Value::Text(_) => "text",
            Value::List(_) => "list",
            Value::Object(_) => "object",
        }
    }

    /// Compares the data type from the registry with the actual value.
    /// `document` and `list` are not checked in depth: the inner field names
    /// cannot be measured on this device (see SAMSUNG-PARAMETERS.md), so a
    /// check there would be a guess.
    pub fn matches_type(&self, data_type: &str) -> bool {
        match data_type {
            "long" => matches!(self, Value::Int(_)),
            "double" => matches!(self, Value::Float(_) | Value::Int(_)),
            "boolean" => matches!(self, Value::Bool(_)),
            "string" => matches!(self, Value::Text(_)),
            "list-of-text" => match self {
                Value::List(l) => l.iter().all(|x| matches!(x, Value::Text(_))),
                _ => false,
            },
            "document" => matches!(self, Value::Object(_)),
            "" => true,
            _ => true, // unknown registry type: do not check against it
        }
    }

    pub fn write(&self) -> String {
        let mut s = String::new();
        self.write_into(&mut s);
        s
    }

    fn write_into(&self, s: &mut String) {
        match self {
            Value::Null => s.push_str("null"),
            Value::Bool(b) => s.push_str(if *b { "true" } else { "false" }),
            Value::Int(z) => {
                let _ = write!(s, "{}", z);
            }
            Value::Float(k) => {
                if k.fract() == 0.0 && k.abs() < 1e15 {
                    let _ = write!(s, "{:.1}", k);
                } else {
                    let _ = write!(s, "{}", k);
                }
            }
            Value::Text(t) => write_string(t, s),
            Value::List(l) => {
                s.push('[');
                for (i, x) in l.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    x.write_into(s);
                }
                s.push(']');
            }
            Value::Object(o) => {
                s.push('{');
                for (i, (k, v)) in o.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    write_string(k, s);
                    s.push(':');
                    v.write_into(s);
                }
                s.push('}');
            }
        }
    }

    /// Reads a text as a JSON value. A bare word is not accepted.
    pub fn read(text: &str) -> Result<Value, String> {
        let mut l = Reader { z: text.as_bytes(), i: 0 };
        l.skip_ws();
        let w = l.value()?;
        l.skip_ws();
        if l.i != l.z.len() {
            return Err(format!("trailing data from character {}", l.i + 1));
        }
        Ok(w)
    }
}

fn write_string(t: &str, s: &mut String) {
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

struct Reader<'a> {
    z: &'a [u8],
    i: usize,
}

impl<'a> Reader<'a> {
    fn skip_ws(&mut self) {
        while self.i < self.z.len() && (self.z[self.i] as char).is_whitespace() {
            self.i += 1;
        }
    }

    fn peek(&self) -> Option<u8> {
        self.z.get(self.i).copied()
    }

    fn expect(&mut self, c: u8) -> Result<(), String> {
        if self.peek() == Some(c) {
            self.i += 1;
            Ok(())
        } else {
            Err(format!(
                "expected '{}' at character {}",
                c as char,
                self.i + 1
            ))
        }
    }

    fn value(&mut self) -> Result<Value, String> {
        self.skip_ws();
        match self.peek() {
            None => Err("unexpected end".to_string()),
            Some(b'{') => {
                self.i += 1;
                let mut m = BTreeMap::new();
                self.skip_ws();
                if self.peek() == Some(b'}') {
                    self.i += 1;
                    return Ok(Value::Object(m));
                }
                loop {
                    self.skip_ws();
                    let k = self.text()?;
                    self.skip_ws();
                    self.expect(b':')?;
                    let v = self.value()?;
                    m.insert(k, v);
                    self.skip_ws();
                    match self.peek() {
                        Some(b',') => {
                            self.i += 1;
                        }
                        Some(b'}') => {
                            self.i += 1;
                            return Ok(Value::Object(m));
                        }
                        _ => return Err(format!("expected ',' or '}}' at character {}", self.i + 1)),
                    }
                }
            }
            Some(b'[') => {
                self.i += 1;
                let mut l = Vec::new();
                self.skip_ws();
                if self.peek() == Some(b']') {
                    self.i += 1;
                    return Ok(Value::List(l));
                }
                loop {
                    l.push(self.value()?);
                    self.skip_ws();
                    match self.peek() {
                        Some(b',') => {
                            self.i += 1;
                        }
                        Some(b']') => {
                            self.i += 1;
                            return Ok(Value::List(l));
                        }
                        _ => return Err(format!("expected ',' or ']' at character {}", self.i + 1)),
                    }
                }
            }
            Some(b'"') => Ok(Value::Text(self.text()?)),
            Some(b't') => self.word("true", Value::Bool(true)),
            Some(b'f') => self.word("false", Value::Bool(false)),
            Some(b'n') => self.word("null", Value::Null),
            Some(_) => self.number(),
        }
    }

    fn word(&mut self, w: &str, v: Value) -> Result<Value, String> {
        if self.z[self.i..].starts_with(w.as_bytes()) {
            self.i += w.len();
            Ok(v)
        } else {
            Err(format!("unknown word at character {}", self.i + 1))
        }
    }

    fn number(&mut self) -> Result<Value, String> {
        let start = self.i;
        if self.peek() == Some(b'-') {
            self.i += 1;
        }
        let mut is_float = false;
        while let Some(c) = self.peek() {
            match c {
                b'0'..=b'9' => self.i += 1,
                b'.' | b'e' | b'E' | b'+' | b'-' => {
                    if c == b'.' {
                        is_float = true;
                    }
                    self.i += 1;
                }
                _ => break,
            }
        }
        let raw = std::str::from_utf8(&self.z[start..self.i]).unwrap_or("");
        if raw.is_empty() {
            return Err(format!("no number at character {}", start + 1));
        }
        if is_float {
            raw.parse::<f64>()
                .map(Value::Float)
                .map_err(|e| format!("floating point number '{}' unreadable: {}", raw, e))
        } else {
            match raw.parse::<i64>() {
                Ok(z) => Ok(Value::Int(z)),
                Err(_) => raw
                    .parse::<f64>()
                    .map(Value::Float)
                    .map_err(|e| format!("number '{}' unreadable: {}", raw, e)),
            }
        }
    }

    fn text(&mut self) -> Result<String, String> {
        self.expect(b'"')?;
        let mut s = String::new();
        loop {
            let c = self
                .peek()
                .ok_or_else(|| "text without end".to_string())?;
            self.i += 1;
            match c {
                b'"' => return Ok(s),
                b'\\' => {
                    let d = self
                        .peek()
                        .ok_or_else(|| "escape character without end".to_string())?;
                    self.i += 1;
                    match d {
                        b'n' => s.push('\n'),
                        b't' => s.push('\t'),
                        b'r' => s.push('\r'),
                        b'b' => s.push('\u{8}'),
                        b'f' => s.push('\u{c}'),
                        b'u' => {
                            if self.i + 4 > self.z.len() {
                                return Err("\\u without four digits".to_string());
                            }
                            let h = std::str::from_utf8(&self.z[self.i..self.i + 4])
                                .map_err(|_| "\\u is not UTF-8".to_string())?;
                            let n = u32::from_str_radix(h, 16)
                                .map_err(|_| format!("\\u{} is not a hex number", h))?;
                            self.i += 4;
                            s.push(char::from_u32(n).unwrap_or('\u{fffd}'));
                        }
                        d => s.push(d as char),
                    }
                }
                _ => {
                    // pass UTF-8 through
                    let start = self.i - 1;
                    let len = utf8_len(c);
                    self.i = start + len;
                    let st = std::str::from_utf8(&self.z[start..self.i])
                        .map_err(|_| "text is not UTF-8".to_string())?;
                    s.push_str(st);
                }
            }
        }
    }
}

fn utf8_len(first: u8) -> usize {
    if first < 0x80 {
        1
    } else if first >> 5 == 0b110 {
        2
    } else if first >> 4 == 0b1110 {
        3
    } else {
        4
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn number_stays_number() {
        let w = Value::read("{\"maxCount\":3}").unwrap();
        match w {
            Value::Object(m) => assert_eq!(m["maxCount"], Value::Int(3)),
            _ => panic!("not an object"),
        }
    }

    #[test]
    fn number_as_text_stays_text() {
        // Counter-check: the platform silently accepts "3" and returns an empty
        // list. The reader must not smooth that over.
        let w = Value::read("{\"maxCount\":\"3\"}").unwrap();
        match w {
            Value::Object(m) => assert_eq!(m["maxCount"], Value::Text("3".to_string())),
            _ => panic!("not an object"),
        }
    }

    #[test]
    fn non_ascii_and_list_round_trip() {
        let w = Value::read("{\"q\":\"café\",\"ids\":[\"73\",\"74\"]}").unwrap();
        let s = w.write();
        assert_eq!(Value::read(&s).unwrap(), w);
    }

    #[test]
    fn trailing_data_is_an_error() {
        assert!(Value::read("{\"a\":1} x").is_err());
    }
}
