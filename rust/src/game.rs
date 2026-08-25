use serde::Serialize;
use shakmaty::san::SanPlus;
use shakmaty::zobrist::{Zobrist64, ZobristHash};
use shakmaty::{
    CastlingMode, CastlingSide, Chess, Color, EnPassantMode, File, Move, Position, Rank, Role,
    Square,
};

use crate::search::choose_move;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GameMode {
    TwoPlayer,
    VsAi,
}

#[derive(Serialize, Clone)]
pub struct PieceDto {
    #[serde(rename = "type")]
    pub kind: String,
    pub color: String,
}

#[derive(Serialize)]
pub struct UiState {
    pub pieces: Vec<Vec<Option<PieceDto>>>,
    #[serde(rename = "capturedByWhite")]
    pub captured_by_white: Vec<PieceDto>,
    #[serde(rename = "capturedByBlack")]
    pub captured_by_black: Vec<PieceDto>,
    pub moves: Vec<String>,
    pub selected: Option<[u8; 2]>,
    #[serde(rename = "legalMoves")]
    pub legal_moves: Vec<[u8; 2]>,
    #[serde(rename = "lastMove")]
    pub last_move: Option<[u8; 4]>,
    pub turn: String,
    #[serde(rename = "gameStatus")]
    pub game_status: Option<String>,
    #[serde(rename = "gameOver")]
    pub game_over: bool,
    #[serde(rename = "canUndo")]
    pub can_undo: bool,
    #[serde(rename = "promotionPending")]
    pub promotion_pending: bool,
    #[serde(rename = "promotionColor")]
    pub promotion_color: Option<String>,
    #[serde(rename = "isBoardFlipped")]
    pub is_board_flipped: bool,
    #[serde(rename = "kingInCheck")]
    pub king_in_check: bool,
}

#[derive(Clone)]
struct Snapshot {
    pos: Chess,
    captured_by_white: Vec<PieceDto>,
    captured_by_black: Vec<PieceDto>,
    moves: Vec<String>,
    last_move: Option<(Square, Square)>,
    position_hashes: Vec<u64>,
}

pub struct Game {
    pos: Chess,
    captured_by_white: Vec<PieceDto>,
    captured_by_black: Vec<PieceDto>,
    moves: Vec<String>,
    selected: Option<(u8, u8)>,
    mode: GameMode,
    player_color: Color,
    difficulty: u8,
    flipped: bool,
    pending_promotion: Option<(Square, Square)>,
    last_move: Option<(Square, Square)>,
    position_hashes: Vec<u64>,
    undo_stack: Vec<Snapshot>,
}

fn role_name(role: Role) -> &'static str {
    match role {
        Role::King => "KING",
        Role::Queen => "QUEEN",
        Role::Rook => "ROOK",
        Role::Bishop => "BISHOP",
        Role::Knight => "KNIGHT",
        Role::Pawn => "PAWN",
    }
}

fn color_name(color: Color) -> &'static str {
    match color {
        Color::White => "WHITE",
        Color::Black => "BLACK",
    }
}

fn parse_role(name: &str) -> Option<Role> {
    match name.to_ascii_uppercase().as_str() {
        "KING" => Some(Role::King),
        "QUEEN" => Some(Role::Queen),
        "ROOK" => Some(Role::Rook),
        "BISHOP" => Some(Role::Bishop),
        "KNIGHT" => Some(Role::Knight),
        "PAWN" => Some(Role::Pawn),
        _ => None,
    }
}

fn square_from_rc(row: u8, col: u8) -> Option<Square> {
    if row > 7 || col > 7 {
        return None;
    }
    Some(Square::from_coords(
        File::new(u32::from(col)),
        Rank::new(u32::from(7 - row)),
    ))
}

fn rc_from_square(sq: Square) -> (u8, u8) {
    let row = 7 - u8::from(sq.rank());
    let col = u8::from(sq.file());
    (row, col)
}

fn piece_dto(role: Role, color: Color) -> PieceDto {
    PieceDto {
        kind: role_name(role).to_string(),
        color: color_name(color).to_string(),
    }
}

fn move_from_to(m: &Move) -> (Square, Square) {
    match *m {
        Move::Normal { from, to, .. } | Move::EnPassant { from, to } => (from, to),
        Move::Castle { king, rook } => {
            let side = CastlingSide::from_queen_side(rook < king);
            (king, Square::from_coords(side.king_to_file(), king.rank()))
        }
        Move::Put { to, .. } => (to, to),
    }
}

fn hash_pos(pos: &Chess) -> u64 {
    let Zobrist64(h) = pos.zobrist_hash::<Zobrist64>(EnPassantMode::Legal);
    h
}

impl Game {
    pub fn new(vs_ai: bool, play_as_white: bool, difficulty: u8) -> Self {
        let pos = Chess::default();
        let player_color = if play_as_white {
            Color::White
        } else {
            Color::Black
        };
        Self {
            position_hashes: vec![hash_pos(&pos)],
            pos,
            captured_by_white: Vec::new(),
            captured_by_black: Vec::new(),
            moves: Vec::new(),
            selected: None,
            mode: if vs_ai {
                GameMode::VsAi
            } else {
                GameMode::TwoPlayer
            },
            player_color,
            difficulty,
            flipped: vs_ai && !play_as_white,
            pending_promotion: None,
            last_move: None,
            undo_stack: Vec::new(),
        }
    }

    pub fn is_ai_turn(&self) -> bool {
        self.mode == GameMode::VsAi
            && self.pending_promotion.is_none()
            && !self.is_game_over()
            && self.pos.turn() != self.player_color
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            pos: self.pos.clone(),
            captured_by_white: self.captured_by_white.clone(),
            captured_by_black: self.captured_by_black.clone(),
            moves: self.moves.clone(),
            last_move: self.last_move,
            position_hashes: self.position_hashes.clone(),
        }
    }

    fn restore(&mut self, snap: Snapshot) {
        self.pos = snap.pos;
        self.captured_by_white = snap.captured_by_white;
        self.captured_by_black = snap.captured_by_black;
        self.moves = snap.moves;
        self.selected = None;
        self.pending_promotion = None;
        self.last_move = snap.last_move;
        self.position_hashes = snap.position_hashes;
    }

    pub fn click(&mut self, row: u8, col: u8) {
        if self.is_game_over() || self.pending_promotion.is_some() {
            return;
        }
        if self.mode == GameMode::VsAi && self.pos.turn() != self.player_color {
            return;
        }
        let Some(sq) = square_from_rc(row, col) else {
            return;
        };

        if let Some((sr, sc)) = self.selected {
            if sr == row && sc == col {
                self.selected = None;
                return;
            }
            let from = square_from_rc(sr, sc).unwrap();
            let matching: Vec<Move> = self
                .pos
                .legal_moves()
                .into_iter()
                .filter(|m| {
                    let (f, t) = move_from_to(m);
                    f == from && t == sq
                })
                .collect();
            if matching.is_empty() {
                self.selected = None;
                if self
                    .pos
                    .board()
                    .piece_at(sq)
                    .is_some_and(|p| p.color == self.pos.turn())
                {
                    self.selected = Some((row, col));
                }
                return;
            }
            if matching.iter().any(|m| m.promotion().is_some()) {
                self.pending_promotion = Some((from, sq));
                self.selected = None;
                return;
            }
            self.play_move(matching.into_iter().next().unwrap());
        } else if self
            .pos
            .board()
            .piece_at(sq)
            .is_some_and(|p| p.color == self.pos.turn())
        {
            self.selected = Some((row, col));
        }
    }

    pub fn promote(&mut self, role_name: &str) {
        let Some(role) = parse_role(role_name) else {
            return;
        };
        if !matches!(
            role,
            Role::Queen | Role::Rook | Role::Bishop | Role::Knight
        ) {
            return;
        }
        let Some((from, to)) = self.pending_promotion else {
            return;
        };
        let matching = self.pos.legal_moves().into_iter().find(|m| {
            let (f, t) = move_from_to(m);
            f == from && t == to && m.promotion() == Some(role)
        });
        if let Some(m) = matching {
            self.pending_promotion = None;
            self.play_move(m);
        }
    }

    pub fn play_ai(&mut self) {
        if !self.is_ai_turn() {
            return;
        }
        if let Some(m) = choose_move(&self.pos, self.difficulty) {
            self.play_move(m);
        }
    }

    pub fn undo(&mut self) {
        if self.pending_promotion.is_some() {
            self.pending_promotion = None;
            self.selected = None;
            return;
        }
        if self.undo_stack.is_empty() {
            return;
        }
        let snap = self.undo_stack.pop().unwrap();
        self.restore(snap);
        if self.mode == GameMode::VsAi
            && self.pos.turn() != self.player_color
            && !self.undo_stack.is_empty()
        {
            let snap = self.undo_stack.pop().unwrap();
            self.restore(snap);
        }
    }

    fn play_move(&mut self, m: Move) {
        self.undo_stack.push(self.snapshot());
        if let Some(role) = m.capture() {
            let captured_color = !self.pos.turn();
            let dto = piece_dto(role, captured_color);
            if self.pos.turn() == Color::White {
                self.captured_by_white.push(dto);
            } else {
                self.captured_by_black.push(dto);
            }
        }
        let (from, to) = move_from_to(&m);
        let san = SanPlus::from_move(self.pos.clone(), &m).to_string();
        self.pos.play_unchecked(&m);
        self.moves.push(san);
        self.selected = None;
        self.last_move = Some((from, to));
        self.position_hashes.push(hash_pos(&self.pos));
    }

    fn is_threefold(&self) -> bool {
        let Some(&current) = self.position_hashes.last() else {
            return false;
        };
        self.position_hashes
            .iter()
            .filter(|h| **h == current)
            .count()
            >= 3
    }

    pub fn is_game_over(&self) -> bool {
        self.pos.is_checkmate()
            || self.pos.is_stalemate()
            || self.pos.is_insufficient_material()
            || self.pos.halfmoves() >= 100
            || self.is_threefold()
    }

    fn legal_dests(&self) -> Vec<[u8; 2]> {
        let Some((row, col)) = self.selected else {
            return Vec::new();
        };
        let Some(from) = square_from_rc(row, col) else {
            return Vec::new();
        };
        let mut dests = Vec::new();
        for m in self.pos.legal_moves() {
            let (f, t) = move_from_to(&m);
            if f == from {
                let (r, c) = rc_from_square(t);
                let pair = [r, c];
                if !dests.contains(&pair) {
                    dests.push(pair);
                }
            }
        }
        dests
    }

    fn status_text(&self) -> Option<String> {
        if self.pos.is_checkmate() {
            let winner = if self.pos.turn() == Color::White {
                "Black"
            } else {
                "White"
            };
            return Some(format!("Checkmate! {winner} wins."));
        }
        if self.pos.is_stalemate() {
            return Some("Stalemate! Draw.".to_string());
        }
        if self.pos.is_insufficient_material() {
            return Some("Draw by insufficient material.".to_string());
        }
        if self.pos.halfmoves() >= 100 {
            return Some("Draw by 50-move rule.".to_string());
        }
        if self.is_threefold() {
            return Some("Draw by threefold repetition.".to_string());
        }
        if self.pos.is_check() {
            return Some("Check!".to_string());
        }
        None
    }

    pub fn ui_state(&self) -> UiState {
        let mut pieces = vec![vec![None; 8]; 8];
        for sq in self.pos.board().occupied() {
            let piece = self.pos.board().piece_at(sq).unwrap();
            let (row, col) = rc_from_square(sq);
            pieces[row as usize][col as usize] = Some(piece_dto(piece.role, piece.color));
        }
        let last_move = self.last_move.map(|(from, to)| {
            let (fr, fc) = rc_from_square(from);
            let (tr, tc) = rc_from_square(to);
            [fr, fc, tr, tc]
        });
        let game_over = self.is_game_over();
        UiState {
            pieces,
            captured_by_white: self.captured_by_white.clone(),
            captured_by_black: self.captured_by_black.clone(),
            moves: self.moves.clone(),
            selected: self.selected.map(|(r, c)| [r, c]),
            legal_moves: self.legal_dests(),
            last_move,
            turn: color_name(self.pos.turn()).to_string(),
            game_status: self.status_text(),
            game_over,
            can_undo: self.pending_promotion.is_some() || !self.undo_stack.is_empty(),
            promotion_pending: self.pending_promotion.is_some(),
            promotion_color: self
                .pending_promotion
                .map(|_| color_name(self.pos.turn()).to_string()),
            is_board_flipped: self.flipped,
            king_in_check: self.pos.is_check() && !game_over,
        }
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(&self.ui_state()).expect("ui state serializes")
    }

    pub fn play_uci_for_tests(&mut self, uci: &str) -> bool {
        let Some(m) = self
            .pos
            .legal_moves()
            .into_iter()
            .find(|m| m.to_uci(CastlingMode::Standard).to_string() == uci)
        else {
            return false;
        };
        self.play_move(m);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_is_whites_turn() {
        let g = Game::new(false, true, 1);
        assert_eq!(g.ui_state().turn, "WHITE");
        assert_eq!(g.ui_state().pieces[6][4].as_ref().unwrap().kind, "PAWN");
        assert_eq!(g.ui_state().pieces[7][4].as_ref().unwrap().kind, "KING");
        assert!(!g.ui_state().can_undo);
    }

    #[test]
    fn e2e4_is_legal() {
        let mut g = Game::new(false, true, 1);
        g.click(6, 4);
        assert!(g.ui_state().legal_moves.contains(&[4, 4]));
        g.click(4, 4);
        assert_eq!(g.moves, vec!["e4"]);
        assert_eq!(g.ui_state().turn, "BLACK");
        assert_eq!(g.ui_state().last_move, Some([6, 4, 4, 4]));
        assert!(g.ui_state().can_undo);
    }

    #[test]
    fn undo_restores_position() {
        let mut g = Game::new(false, true, 1);
        g.click(6, 4);
        g.click(4, 4);
        g.undo();
        assert!(g.moves.is_empty());
        assert_eq!(g.ui_state().turn, "WHITE");
        assert!(g.ui_state().pieces[6][4].is_some());
    }

    #[test]
    fn vs_ai_undo_takes_back_full_move() {
        let mut g = Game::new(true, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        assert!(g.play_uci_for_tests("e7e5"));
        g.undo();
        assert!(g.moves.is_empty());
        assert_eq!(g.ui_state().turn, "WHITE");
    }

    #[test]
    fn illegal_move_rejected() {
        let mut g = Game::new(false, true, 1);
        g.click(6, 4);
        g.click(3, 4);
        assert!(g.moves.is_empty());
    }

    #[test]
    fn scholars_mate() {
        let mut g = Game::new(false, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        assert!(g.play_uci_for_tests("e7e5"));
        assert!(g.play_uci_for_tests("d1h5"));
        assert!(g.play_uci_for_tests("b8c6"));
        assert!(g.play_uci_for_tests("f1c4"));
        assert!(g.play_uci_for_tests("g8f6"));
        assert!(g.play_uci_for_tests("h5f7"));
        let state = g.ui_state();
        assert!(state.game_status.unwrap().contains("Checkmate"));
        assert!(g.is_game_over());
    }

    #[test]
    fn promotion_waits_for_choice() {
        let mut g = Game::new(false, true, 1);
        g.pos = "8/P7/8/8/8/8/8/K6k w - - 0 1"
            .parse::<shakmaty::fen::Fen>()
            .unwrap()
            .into_position(shakmaty::CastlingMode::Standard)
            .unwrap();
        g.position_hashes = vec![hash_pos(&g.pos)];
        g.click(1, 0);
        g.click(0, 0);
        assert!(g.pending_promotion.is_some());
        assert!(!g.is_game_over());
        g.undo();
        assert!(g.pending_promotion.is_none());
        g.click(1, 0);
        g.click(0, 0);
        g.promote("QUEEN");
        assert_eq!(g.ui_state().pieces[0][0].as_ref().unwrap().kind, "QUEEN");
    }

    #[test]
    fn engine_returns_legal_move() {
        let g = Game::new(true, true, 1);
        let mv = crate::search::choose_move(&g.pos, 1).unwrap();
        assert!(g.pos.legal_moves().contains(&mv));
    }

    #[test]
    fn threefold_repetition_is_draw() {
        let mut g = Game::new(false, true, 1);
        for uci in ["g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"] {
            assert!(g.play_uci_for_tests(uci));
        }
        let status = g.ui_state().game_status.unwrap();
        assert!(status.contains("threefold"), "{status}");
        assert!(g.is_game_over());
    }

    #[test]
    fn insufficient_material_is_draw() {
        let mut g = Game::new(false, true, 1);
        g.pos = "8/8/8/8/8/8/8/K6k w - - 0 1"
            .parse::<shakmaty::fen::Fen>()
            .unwrap()
            .into_position(shakmaty::CastlingMode::Standard)
            .unwrap();
        g.position_hashes = vec![hash_pos(&g.pos)];
        assert!(g.is_game_over());
        assert!(g
            .ui_state()
            .game_status
            .unwrap()
            .contains("insufficient"));
    }

    #[test]
    fn fifty_move_rule_is_draw() {
        let mut g = Game::new(false, true, 1);
        g.pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 100 50"
            .parse::<shakmaty::fen::Fen>()
            .unwrap()
            .into_position(shakmaty::CastlingMode::Standard)
            .unwrap();
        g.position_hashes = vec![hash_pos(&g.pos)];
        assert!(g.is_game_over());
        assert!(g.ui_state().game_status.unwrap().contains("50-move"));
    }
}
