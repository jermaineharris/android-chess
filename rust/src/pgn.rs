use shakmaty::san::San;
use shakmaty::{CastlingMode, Chess, Position};

use crate::game::Game;

pub fn import_pgn_or_fen(
    text: &str,
    vs_ai: bool,
    play_as_white: bool,
    difficulty: u8,
) -> Result<Game, String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err("empty import".to_string());
    }
    if looks_like_fen(trimmed) {
        return import_fen(trimmed, vs_ai, play_as_white, difficulty);
    }
    import_pgn(trimmed, vs_ai, play_as_white, difficulty)
}

fn looks_like_fen(text: &str) -> bool {
    let line = text.lines().next().unwrap_or("").trim();
    let parts: Vec<&str> = line.split_whitespace().collect();
    parts.len() >= 4 && parts[0].contains('/') && !line.starts_with('[')
}

fn import_fen(
    fen: &str,
    vs_ai: bool,
    play_as_white: bool,
    difficulty: u8,
) -> Result<Game, String> {
    let mut game = Game::new(vs_ai, play_as_white, difficulty);
    game.load_fen(fen)?;
    Ok(game)
}

fn header_fen(pgn: &str) -> Option<String> {
    let lower = pgn.to_ascii_lowercase();
    let idx = lower.find("[fen")?;
    let rest = &pgn[idx..];
    let start = rest.find('"')?;
    let end = rest[start + 1..].find('"')?;
    Some(rest[start + 1..start + 1 + end].to_string())
}

fn strip_noise(pgn: &str) -> String {
    let mut out = String::new();
    let mut chars = pgn.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '{' => {
                for next in chars.by_ref() {
                    if next == '}' {
                        break;
                    }
                }
            }
            ';' => {
                for next in chars.by_ref() {
                    if next == '\n' {
                        break;
                    }
                }
            }
            '(' => {
                let mut depth = 1;
                for next in chars.by_ref() {
                    if next == '(' {
                        depth += 1;
                    } else if next == ')' {
                        depth -= 1;
                        if depth == 0 {
                            break;
                        }
                    }
                }
            }
            _ => out.push(c),
        }
    }
    out
}

fn import_pgn(
    pgn: &str,
    vs_ai: bool,
    play_as_white: bool,
    difficulty: u8,
) -> Result<Game, String> {
    let mut game = Game::new(vs_ai, play_as_white, difficulty);
    if let Some(fen) = header_fen(pgn) {
        game.load_fen(&fen)?;
    }
    let body = strip_headers(&strip_noise(pgn));
    let mut pos: Chess = game.position_clone();
    for token in body.split_whitespace() {
        let tok = token.trim_matches(|c: char| matches!(c, '.' | '!' | '?'));
        if tok.is_empty() || is_result(tok) || looks_like_move_number(tok) {
            continue;
        }
        let san = San::from_ascii(tok.as_bytes()).map_err(|_| format!("bad SAN {tok}"))?;
        let m = san.to_move(&pos).map_err(|_| format!("illegal SAN {tok}"))?;
        if !game.play_uci_for_tests(&m.to_uci(CastlingMode::Standard).to_string()) {
            return Err(format!("could not play {tok}"));
        }
        pos.play_unchecked(&m);
    }
    game.clear_redo();
    game.set_last_event("none");
    Ok(game)
}

fn strip_headers(pgn: &str) -> String {
    pgn.lines()
        .filter(|line| !line.trim_start().starts_with('['))
        .collect::<Vec<_>>()
        .join(" ")
}

fn is_result(tok: &str) -> bool {
    matches!(tok, "*" | "1-0" | "0-1" | "1/2-1/2" | "½-½")
}

fn looks_like_move_number(tok: &str) -> bool {
    tok.chars().all(|c| c.is_ascii_digit() || c == '.')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imports_e4_e5_pgn() {
        let g = import_pgn_or_fen("1. e4 e5", false, true, 1).unwrap();
        assert_eq!(g.ui_state().moves, vec!["e4", "e5"]);
        assert_eq!(g.ui_state().turn, "WHITE");
    }

    #[test]
    fn imports_fen_after_e4() {
        let g = import_pgn_or_fen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            false,
            true,
            1,
        )
        .unwrap();
        assert_eq!(g.ui_state().turn, "BLACK");
        assert!(g.ui_state().pieces[4][4].is_some());
    }

    #[test]
    fn strips_comments_and_variations() {
        let g = import_pgn_or_fen(
            r#"[Event "x"]
1. e4 {comment} e5 (1... c5 2. Nf3) 2. Nf3 Nc6"#,
            false,
            true,
            1,
        )
        .unwrap();
        assert_eq!(g.ui_state().moves, vec!["e4", "e5", "Nf3", "Nc6"]);
    }
}
