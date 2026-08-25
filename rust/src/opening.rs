use serde::Serialize;
use shakmaty::san::SanPlus;
use shakmaty::{CastlingMode, Chess, Position};

#[derive(Serialize, Clone)]
pub struct OpeningChild {
    pub san: String,
    pub uci: String,
    pub eco: String,
    pub name: String,
    pub lines: u32,
}

pub struct OpeningInfo {
    pub eco: Option<String>,
    pub name: Option<String>,
    pub children: Vec<OpeningChild>,
}

struct Line {
    eco: &'static str,
    name: &'static str,
    uci: &'static [&'static str],
}

/// Named ECO lines used as an offline opening tree (not Lichess explorer stats).
const LINES: &[Line] = &[
    Line { eco: "B20", name: "Sicilian Defense", uci: &["e2e4", "c7c5"] },
    Line { eco: "B70", name: "Sicilian Dragon", uci: &["e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "g7g6"] },
    Line { eco: "B90", name: "Sicilian Najdorf", uci: &["e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6"] },
    Line { eco: "B33", name: "Sicilian Sveshnikov", uci: &["e2e4", "c7c5", "g1f3", "b8c6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "e7e5"] },
    Line { eco: "B40", name: "Sicilian Kan", uci: &["e2e4", "c7c5", "g1f3", "e7e6"] },
    Line { eco: "B27", name: "Sicilian Hyperaccelerated Dragon", uci: &["e2e4", "c7c5", "g1f3", "g7g6"] },
    Line { eco: "C60", name: "Ruy Lopez", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5"] },
    Line { eco: "C65", name: "Ruy Lopez Berlin", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "g8f6"] },
    Line { eco: "C68", name: "Ruy Lopez Exchange", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6"] },
    Line { eco: "C89", name: "Ruy Lopez Marshall", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7", "f1e1", "b7b5", "a4b3", "e8g8", "c2c3", "d7d5"] },
    Line { eco: "C50", name: "Italian Game", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1c4"] },
    Line { eco: "C53", name: "Giuoco Piano", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5"] },
    Line { eco: "C57", name: "Two Knights Defense", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6"] },
    Line { eco: "C41", name: "Philidor Defense", uci: &["e2e4", "e7e5", "g1f3", "d7d6"] },
    Line { eco: "C42", name: "Petrov Defense", uci: &["e2e4", "e7e5", "g1f3", "g8f6"] },
    Line { eco: "C45", name: "Scotch Game", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "d2d4"] },
    Line { eco: "C25", name: "Vienna Game", uci: &["e2e4", "e7e5", "b1c3"] },
    Line { eco: "C30", name: "King's Gambit", uci: &["e2e4", "e7e5", "f2f4"] },
    Line { eco: "C20", name: "King's Pawn Game", uci: &["e2e4", "e7e5"] },
    Line { eco: "B00", name: "King's Pawn", uci: &["e2e4"] },
    Line { eco: "B10", name: "Caro-Kann Defense", uci: &["e2e4", "c7c6"] },
    Line { eco: "B12", name: "Caro-Kann Advance", uci: &["e2e4", "c7c6", "d2d4", "d7d5", "e4e5"] },
    Line { eco: "B18", name: "Caro-Kann Classical", uci: &["e2e4", "c7c6", "d2d4", "d7d5", "b1c3", "d5e4", "c3e4", "c8f5"] },
    Line { eco: "C00", name: "French Defense", uci: &["e2e4", "e7e6"] },
    Line { eco: "C02", name: "French Advance", uci: &["e2e4", "e7e6", "d2d4", "d7d5", "e4e5"] },
    Line { eco: "C10", name: "French Rubinstein", uci: &["e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "d5e4"] },
    Line { eco: "C11", name: "French Steinitz", uci: &["e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "g8f6"] },
    Line { eco: "C18", name: "French Winawer", uci: &["e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "f8b4"] },
    Line { eco: "B07", name: "Pirc Defense", uci: &["e2e4", "d7d6"] },
    Line { eco: "B06", name: "Modern Defense", uci: &["e2e4", "g7g6"] },
    Line { eco: "B01", name: "Scandinavian Defense", uci: &["e2e4", "d7d5"] },
    Line { eco: "C44", name: "Ponziani Opening", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "c2c3"] },
    Line { eco: "A00", name: "Van't Kruijs Opening", uci: &["e2e3"] },
    Line { eco: "A40", name: "Queen's Pawn", uci: &["d2d4"] },
    Line { eco: "D00", name: "Queen's Pawn Game", uci: &["d2d4", "d7d5"] },
    Line { eco: "D06", name: "Queen's Gambit", uci: &["d2d4", "d7d5", "c2c4"] },
    Line { eco: "D10", name: "Slav Defense", uci: &["d2d4", "d7d5", "c2c4", "c7c6"] },
    Line { eco: "D30", name: "Queen's Gambit Declined", uci: &["d2d4", "d7d5", "c2c4", "e7e6"] },
    Line { eco: "D20", name: "Queen's Gambit Accepted", uci: &["d2d4", "d7d5", "c2c4", "d5c4"] },
    Line { eco: "D80", name: "Grünfeld Defense", uci: &["d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5"] },
    Line { eco: "E60", name: "King's Indian Defense", uci: &["d2d4", "g8f6", "c2c4", "g7g6"] },
    Line { eco: "E80", name: "King's Indian Sämisch", uci: &["d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "e2e4", "d7d6", "f2f3"] },
    Line { eco: "E90", name: "King's Indian Classical", uci: &["d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "e2e4", "d7d6", "g1f3"] },
    Line { eco: "E00", name: "Catalan Opening", uci: &["d2d4", "g8f6", "c2c4", "e7e6", "g2g3"] },
    Line { eco: "A50", name: "Indian Defense", uci: &["d2d4", "g8f6"] },
    Line { eco: "A40", name: "English Defense", uci: &["d2d4", "e7e6"] },
    Line { eco: "A40", name: "Modern Defense", uci: &["d2d4", "g7g6"] },
    Line { eco: "A43", name: "Benoni Defense", uci: &["d2d4", "c7c5"] },
    Line { eco: "A52", name: "Budapest Gambit", uci: &["d2d4", "g8f6", "c2c4", "e7e5"] },
    Line { eco: "E20", name: "Nimzo-Indian Defense", uci: &["d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4"] },
    Line { eco: "E12", name: "Queen's Indian Defense", uci: &["d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "b7b6"] },
    Line { eco: "A80", name: "Dutch Defense", uci: &["d2d4", "f7f5"] },
    Line { eco: "D70", name: "Neo-Grünfeld", uci: &["d2d4", "g8f6", "c2c4", "g7g6", "g2g3", "d7d5"] },
    Line { eco: "A10", name: "English Opening", uci: &["c2c4"] },
    Line { eco: "A20", name: "English, King's", uci: &["c2c4", "e7e5"] },
    Line { eco: "A30", name: "English, Symmetrical", uci: &["c2c4", "c7c5"] },
    Line { eco: "A15", name: "English, Anglo-Indian", uci: &["c2c4", "g8f6"] },
    Line { eco: "A04", name: "Réti Opening", uci: &["g1f3"] },
    Line { eco: "A07", name: "King's Indian Attack", uci: &["g1f3", "d7d5", "g2g3"] },
    Line { eco: "A01", name: "Nimzowitsch-Larsen", uci: &["b2b3"] },
    Line { eco: "A02", name: "Bird's Opening", uci: &["f2f4"] },
    Line { eco: "A00", name: "Grob Opening", uci: &["g2g4"] },
    Line { eco: "A00", name: "Polish Opening", uci: &["b2b4"] },
    Line { eco: "C23", name: "Bishop's Opening", uci: &["e2e4", "e7e5", "f1c4"] },
    Line { eco: "C46", name: "Four Knights Game", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "b1c3", "g8f6"] },
    Line { eco: "C47", name: "Four Knights Scotch", uci: &["e2e4", "e7e5", "g1f3", "b8c6", "b1c3", "g8f6", "d2d4"] },
    Line { eco: "D02", name: "London System", uci: &["d2d4", "d7d5", "g1f3", "g8f6", "c1f4"] },
    Line { eco: "D00", name: "Jobava London", uci: &["d2d4", "d7d5", "b1c3", "g8f6", "c1f4"] },
    Line { eco: "A45", name: "Trompowsky Attack", uci: &["d2d4", "g8f6", "c1g5"] },
    Line { eco: "B02", name: "Alekhine Defense", uci: &["e2e4", "g8f6"] },
    Line { eco: "B00", name: "Nimzowitsch Defense", uci: &["e2e4", "b8c6"] },
    Line { eco: "C00", name: "French Knight", uci: &["e2e4", "e7e6", "d2d4", "d7d5", "b1d2"] },
];

fn replay(uci: &[&str]) -> Option<Chess> {
    let mut pos = Chess::default();
    for u in uci {
        let m = pos
            .legal_moves()
            .into_iter()
            .find(|m| m.to_uci(CastlingMode::Standard).to_string() == *u)?;
        pos.play_unchecked(&m);
    }
    Some(pos)
}

pub fn lookup(played: &[String]) -> OpeningInfo {
    let mut best: Option<&Line> = None;
    for line in LINES {
        if line.uci.len() <= played.len() && line.uci.iter().zip(played).all(|(a, b)| a == b) {
            if best.map(|cur| line.uci.len() >= cur.uci.len()).unwrap_or(true) {
                best = Some(line);
            }
        }
    }
    let mut children: Vec<OpeningChild> = Vec::new();
    for line in LINES {
        if line.uci.len() <= played.len() {
            continue;
        }
        if !line.uci.iter().zip(played).all(|(a, b)| a == b) {
            continue;
        }
        let next = line.uci[played.len()];
        if let Some(existing) = children.iter_mut().find(|c| c.uci == next) {
            existing.lines += 1;
            if line.uci.len() == played.len() + 1 {
                existing.eco = line.eco.to_string();
                existing.name = line.name.to_string();
            }
            continue;
        }
        let Some(pos) = replay(&line.uci[..played.len()]) else {
            continue;
        };
        let Some(m) = pos
            .legal_moves()
            .into_iter()
            .find(|m| m.to_uci(CastlingMode::Standard).to_string() == next)
        else {
            continue;
        };
        let san = SanPlus::from_move(pos, &m).to_string();
        children.push(OpeningChild {
            san,
            uci: next.to_string(),
            eco: line.eco.to_string(),
            name: line.name.to_string(),
            lines: 1,
        });
    }
    children.sort_by(|a, b| b.lines.cmp(&a.lines).then(a.san.cmp(&b.san)));
    OpeningInfo {
        eco: best.map(|l| l.eco.to_string()),
        name: best.map(|l| l.name.to_string()),
        children,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sicilian_after_e4() {
        let info = lookup(&["e2e4".to_string()]);
        assert!(info.children.iter().any(|c| c.uci == "c7c5"));
        let e4 = lookup(&[]).children.into_iter().find(|c| c.uci == "e2e4").unwrap();
        assert_eq!(e4.name, "King's Pawn");
        assert_eq!(e4.eco, "B00");
    }

    #[test]
    fn names_ruy_lopez() {
        let info = lookup(
            &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5"]
                .iter()
                .map(|s| s.to_string())
                .collect::<Vec<_>>(),
        );
        assert_eq!(info.name.as_deref(), Some("Ruy Lopez"));
        assert_eq!(info.eco.as_deref(), Some("C60"));
    }
}
