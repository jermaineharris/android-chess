use serde::Serialize;
use shakmaty::fen::Fen;
use shakmaty::san::SanPlus;
use shakmaty::zobrist::{Zobrist64, ZobristHash};
use shakmaty::{
    CastlingMode, CastlingSide, Chess, Color, EnPassantMode, File, Move, Position, Rank, Role,
    Square,
};

use crate::opening;
use crate::search::{analyze_pv, best_move, choose_move, depth_for_difficulty, evaluate};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GameMode {
    TwoPlayer,
    VsAi,
    Analysis,
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
    pub pgn: String,
    pub material: i32,
    #[serde(rename = "lastEvent")]
    pub last_event: String,
    #[serde(rename = "canRedo")]
    pub can_redo: bool,
    #[serde(rename = "canClaimDraw")]
    pub can_claim_draw: bool,
    #[serde(rename = "canOfferDraw")]
    pub can_offer_draw: bool,
    #[serde(rename = "drawOfferPending")]
    pub draw_offer_pending: bool,
    #[serde(rename = "drawOfferBy")]
    pub draw_offer_by: Option<String>,
    #[serde(rename = "hintsLeft")]
    pub hints_left: i32,
    pub hint: Option<[u8; 4]>,
    pub halfmoves: u32,
    pub fen: String,
    pub ply: u32,
    pub analysis: bool,
    pub eco: Option<String>,
    pub opening: Option<String>,
    #[serde(rename = "openingMoves")]
    pub opening_moves: Vec<opening::OpeningChild>,
}

#[derive(Clone)]
struct Snapshot {
    pos: Chess,
    captured_by_white: Vec<PieceDto>,
    captured_by_black: Vec<PieceDto>,
    moves: Vec<String>,
    last_move: Option<(Square, Square)>,
    position_hashes: Vec<u64>,
    uci_moves: Vec<String>,
    resigned: Option<Color>,
    draw_agreed: bool,
    flagged: Option<Color>,
    pending_draw_offer: Option<Color>,
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
    redo_stack: Vec<Snapshot>,
    uci_moves: Vec<String>,
    resigned: Option<Color>,
    last_event: String,
    draw_agreed: bool,
    flagged: Option<Color>,
    pending_draw_offer: Option<Color>,
    hints_used: u8,
    hint: Option<(Square, Square)>,
    start_fen: String,
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
        let start_fen = Fen::from_position(pos.clone(), EnPassantMode::Legal).to_string();
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
            redo_stack: Vec::new(),
            uci_moves: Vec::new(),
            resigned: None,
            last_event: "none".to_string(),
            draw_agreed: false,
            flagged: None,
            pending_draw_offer: None,
            hints_used: 0,
            hint: None,
            start_fen,
        }
    }

    pub fn analysis_board() -> Self {
        let mut game = Self::new(false, true, 1);
        game.mode = GameMode::Analysis;
        game
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
            uci_moves: self.uci_moves.clone(),
            resigned: self.resigned,
            draw_agreed: self.draw_agreed,
            flagged: self.flagged,
            pending_draw_offer: self.pending_draw_offer,
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
        self.uci_moves = snap.uci_moves;
        self.resigned = snap.resigned;
        self.draw_agreed = snap.draw_agreed;
        self.flagged = snap.flagged;
        self.pending_draw_offer = snap.pending_draw_offer;
        self.hint = None;
        self.last_event = "none".to_string();
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
        let current = self.snapshot();
        let snap = self.undo_stack.pop().unwrap();
        self.restore(snap);
        if self.mode == GameMode::VsAi
            && self.pos.turn() != self.player_color
            && !self.undo_stack.is_empty()
        {
            let snap = self.undo_stack.pop().unwrap();
            self.restore(snap);
        }
        self.redo_stack.push(current);
    }

    pub fn redo(&mut self) {
        let Some(snap) = self.redo_stack.pop() else {
            return;
        };
        self.undo_stack.push(self.snapshot());
        self.restore(snap);
    }

    fn play_move(&mut self, m: Move) {
        self.undo_stack.push(self.snapshot());
        self.redo_stack.clear();
        self.pending_draw_offer = None;
        self.hint = None;
        let captured = m.capture().is_some();
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
        let uci = m.to_uci(CastlingMode::Standard).to_string();
        self.pos.play_unchecked(&m);
        self.moves.push(san);
        self.uci_moves.push(uci);
        self.selected = None;
        self.last_move = Some((from, to));
        self.position_hashes.push(hash_pos(&self.pos));
        self.last_event = if self.pos.is_checkmate() {
            "mate".to_string()
        } else if self.is_game_over() {
            "draw".to_string()
        } else if self.pos.is_check() {
            "check".to_string()
        } else if captured {
            "capture".to_string()
        } else {
            "move".to_string()
        };
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

    fn is_twofold(&self) -> bool {
        let Some(&current) = self.position_hashes.last() else {
            return false;
        };
        self.position_hashes
            .iter()
            .filter(|h| **h == current)
            .count()
            >= 2
    }

    pub fn is_game_over(&self) -> bool {
        self.resigned.is_some()
            || self.draw_agreed
            || self.flagged.is_some()
            || self.pos.is_checkmate()
            || self.pos.is_stalemate()
            || self.pos.is_insufficient_material()
            || self.pos.halfmoves() >= 100
            || self.is_threefold()
    }

    fn can_claim_draw(&self) -> bool {
        !self.is_game_over() && (self.pos.halfmoves() >= 80 || self.is_twofold())
    }

    fn hints_left(&self) -> i32 {
        if self.mode == GameMode::Analysis {
            return 99;
        }
        if self.difficulty == 0 {
            (3i32 - i32::from(self.hints_used)).max(0)
        } else {
            99
        }
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
        if let Some(loser) = self.flagged {
            let winner = if loser == Color::White { "Black" } else { "White" };
            return Some(format!("{winner} wins on time."));
        }
        if self.draw_agreed {
            return Some("Draw by agreement.".to_string());
        }
        if let Some(loser) = self.resigned {
            let winner = if loser == Color::White { "Black" } else { "White" };
            return Some(format!("{winner} wins by resignation."));
        }
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
        if let Some(by) = self.pending_draw_offer {
            let name = if by == Color::White { "White" } else { "Black" };
            return Some(format!("{name} offers a draw."));
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
        let opening = opening::lookup(&self.uci_moves);
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
            pgn: self.pgn(),
            material: self.material_score(),
            last_event: self.last_event.clone(),
            can_redo: !self.redo_stack.is_empty(),
            can_claim_draw: self.can_claim_draw(),
            can_offer_draw: self.mode != GameMode::Analysis
                && !self.is_game_over()
                && self.pending_promotion.is_none()
                && self.pending_draw_offer.is_none(),
            draw_offer_pending: self.pending_draw_offer.is_some(),
            draw_offer_by: self.pending_draw_offer.map(color_name).map(str::to_string),
            hints_left: self.hints_left(),
            hint: self.hint.map(|(from, to)| {
                let (fr, fc) = rc_from_square(from);
                let (tr, tc) = rc_from_square(to);
                [fr, fc, tr, tc]
            }),
            halfmoves: self.pos.halfmoves(),
            fen: Fen::from_position(self.pos.clone(), EnPassantMode::Legal).to_string(),
            ply: self.uci_moves.len() as u32,
            analysis: self.mode == GameMode::Analysis,
            eco: opening.eco,
            opening: opening.name,
            opening_moves: opening.children,
        }
    }

    fn material_score(&self) -> i32 {
        let mut score = 0;
        for sq in self.pos.board().occupied() {
            let piece = self.pos.board().piece_at(sq).unwrap();
            let v = match piece.role {
                Role::Pawn => 1,
                Role::Knight | Role::Bishop => 3,
                Role::Rook => 5,
                Role::Queen => 9,
                Role::King => 0,
            };
            score += if piece.color == Color::White { v } else { -v };
        }
        if self.flipped { -score } else { score }
    }

    fn pgn_result(&self) -> &'static str {
        if let Some(loser) = self.flagged.or(self.resigned) {
            return if loser == Color::White { "0-1" } else { "1-0" };
        }
        if self.draw_agreed {
            return "1/2-1/2";
        }
        if self.pos.is_checkmate() {
            return if self.pos.turn() == Color::White {
                "0-1"
            } else {
                "1-0"
            };
        }
        if self.is_game_over() {
            return "1/2-1/2";
        }
        "*"
    }

    fn pgn(&self) -> String {
        let result = self.pgn_result();
        let mut out = format!(
            "[Event \"Hutts Chess\"]\n[Site \"Android\"]\n[Result \"{result}\"]\n\n"
        );
        for (i, chunk) in self.moves.chunks(2).enumerate() {
            out.push_str(&format!("{}. {}", i + 1, chunk[0]));
            if chunk.len() == 2 {
                out.push(' ');
                out.push_str(&chunk[1]);
            }
            out.push(' ');
        }
        out.push_str(result);
        out.trim().to_string()
    }

    pub fn deselect(&mut self) {
        self.selected = None;
        self.last_event = "none".to_string();
    }

    pub fn toggle_flip(&mut self) {
        self.flipped = !self.flipped;
        self.last_event = "none".to_string();
    }

    pub fn resign(&mut self) {
        if self.is_game_over() {
            return;
        }
        let loser = if self.mode == GameMode::VsAi {
            self.player_color
        } else {
            self.pos.turn()
        };
        self.undo_stack.push(self.snapshot());
        self.resigned = Some(loser);
        self.selected = None;
        self.pending_promotion = None;
        self.last_event = "resign".to_string();
    }

    pub fn flag(&mut self, loser_white: bool) {
        if self.is_game_over() {
            return;
        }
        self.undo_stack.push(self.snapshot());
        self.flagged = Some(if loser_white {
            Color::White
        } else {
            Color::Black
        });
        self.selected = None;
        self.pending_promotion = None;
        self.last_event = "flag".to_string();
    }

    pub fn offer_draw(&mut self) {
        if self.is_game_over() || self.pending_promotion.is_some() {
            return;
        }
        let by = if self.mode == GameMode::VsAi {
            self.player_color
        } else {
            self.pos.turn()
        };
        if self.mode == GameMode::VsAi {
            let stm_eval = evaluate(&self.pos);
            let human_is_stm = self.pos.turn() == self.player_color;
            let human_score = if human_is_stm { stm_eval } else { -stm_eval };
            if human_score <= 80 {
                self.undo_stack.push(self.snapshot());
                self.redo_stack.clear();
                self.draw_agreed = true;
                self.last_event = "draw".to_string();
            } else {
                self.last_event = "draw_declined".to_string();
            }
            return;
        }
        self.pending_draw_offer = Some(by);
        self.last_event = "none".to_string();
    }

    pub fn accept_draw(&mut self) {
        if self.is_game_over() || self.pending_draw_offer.is_none() {
            return;
        }
        self.undo_stack.push(self.snapshot());
        self.redo_stack.clear();
        self.pending_draw_offer = None;
        self.draw_agreed = true;
        self.last_event = "draw".to_string();
    }

    pub fn decline_draw(&mut self) {
        self.pending_draw_offer = None;
        self.last_event = "draw_declined".to_string();
    }

    pub fn claim_draw(&mut self) {
        if !self.can_claim_draw() {
            return;
        }
        self.undo_stack.push(self.snapshot());
        self.redo_stack.clear();
        self.draw_agreed = true;
        self.last_event = "draw".to_string();
    }

    pub fn hint(&mut self) {
        if self.is_game_over() || self.pending_promotion.is_some() {
            return;
        }
        if self.mode == GameMode::VsAi && self.pos.turn() != self.player_color {
            return;
        }
        if self.hints_left() <= 0 {
            self.last_event = "none".to_string();
            return;
        }
        let depth = depth_for_difficulty(self.difficulty.max(1));
        let Some(m) = best_move(&self.pos, depth) else {
            return;
        };
        let (from, to) = move_from_to(&m);
        self.hint = Some((from, to));
        self.hints_used = self.hints_used.saturating_add(1);
        self.last_event = "hint".to_string();
    }

    pub fn load_fen(&mut self, fen: &str) -> Result<(), String> {
        self.pos = fen
            .parse::<Fen>()
            .map_err(|e| e.to_string())?
            .into_position(CastlingMode::Standard)
            .map_err(|e| e.to_string())?;
        self.captured_by_white.clear();
        self.captured_by_black.clear();
        self.moves.clear();
        self.uci_moves.clear();
        self.undo_stack.clear();
        self.redo_stack.clear();
        self.selected = None;
        self.pending_promotion = None;
        self.last_move = None;
        self.resigned = None;
        self.draw_agreed = false;
        self.flagged = None;
        self.pending_draw_offer = None;
        self.hint = None;
        self.position_hashes = vec![hash_pos(&self.pos)];
        self.start_fen = Fen::from_position(self.pos.clone(), EnPassantMode::Legal).to_string();
        self.last_event = "none".to_string();
        Ok(())
    }

    pub fn play_uci(&mut self, uci: &str) -> bool {
        if self.pending_promotion.is_some() {
            return false;
        }
        if self.mode != GameMode::Analysis && self.is_game_over() {
            return false;
        }
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

    pub fn goto_ply(&mut self, ply: i32) {
        let line = self.uci_moves.clone();
        let n = (ply.max(0) as usize).min(line.len());
        let start = self.start_fen.clone();
        let mode = self.mode;
        let player_color = self.player_color;
        let difficulty = self.difficulty;
        let flipped = self.flipped;
        let vs_ai = mode == GameMode::VsAi;
        let play_white = player_color == Color::White;
        *self = if mode == GameMode::Analysis {
            Self::analysis_board()
        } else {
            Self::new(vs_ai, play_white, difficulty)
        };
        self.flipped = flipped;
        self.player_color = player_color;
        self.difficulty = difficulty;
        self.mode = mode;
        if start != self.start_fen {
            let _ = self.reset_position(&start);
            self.start_fen = start;
        }
        for uci in line.iter().take(n) {
            if !self.play_uci_for_tests(uci) {
                break;
            }
        }
        self.redo_stack.clear();
        self.last_event = "none".to_string();
    }

    fn reset_position(&mut self, fen: &str) -> Result<(), String> {
        self.pos = fen
            .parse::<Fen>()
            .map_err(|e| e.to_string())?
            .into_position(CastlingMode::Standard)
            .map_err(|e| e.to_string())?;
        self.captured_by_white.clear();
        self.captured_by_black.clear();
        self.moves.clear();
        self.uci_moves.clear();
        self.undo_stack.clear();
        self.redo_stack.clear();
        self.selected = None;
        self.pending_promotion = None;
        self.last_move = None;
        self.resigned = None;
        self.draw_agreed = false;
        self.flagged = None;
        self.pending_draw_offer = None;
        self.hint = None;
        self.position_hashes = vec![hash_pos(&self.pos)];
        Ok(())
    }

    pub fn rust_analyze(&self, depth: u8) -> String {
        let (score, pv) = analyze_pv(&self.pos, depth.max(1));
        serde_json::json!({
            "cp": score,
            "pv": pv,
            "depth": depth,
            "engine": "hutts",
        })
        .to_string()
    }

    pub fn position_clone(&self) -> Chess {
        self.pos.clone()
    }

    pub fn clear_redo(&mut self) {
        self.redo_stack.clear();
    }

    pub fn set_last_event(&mut self, event: &str) {
        self.last_event = event.to_string();
    }

    pub fn export_save(&self) -> String {
        serde_json::json!({
            "vsAi": self.mode == GameMode::VsAi,
            "playAsWhite": self.player_color == Color::White,
            "difficulty": self.difficulty,
            "flipped": self.flipped,
            "analysis": self.mode == GameMode::Analysis,
            "startFen": self.start_fen,
            "fen": Fen::from_position(self.pos.clone(), EnPassantMode::Legal).to_string(),
            "uci": self.uci_moves,
            "resigned": self.resigned.map(color_name),
            "pendingFrom": self.pending_promotion.map(|(from, _)| from.to_string()),
            "pendingTo": self.pending_promotion.map(|(_, to)| to.to_string()),
            "drawAgreed": self.draw_agreed,
            "flagged": self.flagged.map(color_name),
            "hintsUsed": self.hints_used,
        })
        .to_string()
    }

    pub fn import_save(json: &str) -> Result<Self, String> {
        let v: serde_json::Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
        let vs_ai = v.get("vsAi").and_then(|x| x.as_bool()).unwrap_or(false);
        let play_as_white = v.get("playAsWhite").and_then(|x| x.as_bool()).unwrap_or(true);
        let difficulty = v.get("difficulty").and_then(|x| x.as_u64()).unwrap_or(1) as u8;
        let analysis = v.get("analysis").and_then(|x| x.as_bool()).unwrap_or(false);
        let mut game = if analysis {
            Game::analysis_board()
        } else {
            Game::new(vs_ai, play_as_white, difficulty)
        };
        if let Some(start) = v.get("startFen").and_then(|x| x.as_str()) {
            let _ = game.load_fen(start);
        }
        if let Some(flipped) = v.get("flipped").and_then(|x| x.as_bool()) {
            game.flipped = flipped;
        }
        let mut replayed = 0usize;
        if let Some(arr) = v.get("uci").and_then(|x| x.as_array()) {
            for uci in arr {
                let Some(s) = uci.as_str() else { continue };
                if !game.play_uci_for_tests(s) {
                    return Err(format!("illegal uci {s}"));
                }
                replayed += 1;
            }
        }
        if replayed == 0 {
            if let Some(fen) = v.get("fen").and_then(|x| x.as_str()) {
                game.pos = fen
                    .parse::<Fen>()
                    .map_err(|e| e.to_string())?
                    .into_position(CastlingMode::Standard)
                    .map_err(|e| e.to_string())?;
                game.position_hashes = vec![hash_pos(&game.pos)];
                game.start_fen = fen.to_string();
            }
        }
        let resigned = match v.get("resigned").and_then(|x| x.as_str()) {
            Some("WHITE") => Some(Color::White),
            Some("BLACK") => Some(Color::Black),
            _ => None,
        };
        if let Some(loser) = resigned {
            game.undo_stack.push(game.snapshot());
            game.resigned = Some(loser);
        }
        if v.get("drawAgreed").and_then(|x| x.as_bool()).unwrap_or(false) && game.resigned.is_none()
        {
            game.undo_stack.push(game.snapshot());
            game.draw_agreed = true;
        }
        if let Some(name) = v.get("flagged").and_then(|x| x.as_str()) {
            game.flagged = match name {
                "WHITE" => Some(Color::White),
                "BLACK" => Some(Color::Black),
                _ => None,
            };
        }
        game.hints_used = v.get("hintsUsed").and_then(|x| x.as_u64()).unwrap_or(0) as u8;
        if game.resigned.is_none() {
            let from = v.get("pendingFrom").and_then(|x| x.as_str());
            let to = v.get("pendingTo").and_then(|x| x.as_str());
            if let (Some(from), Some(to)) = (from, to) {
                let from_sq: Square = from
                    .parse()
                    .map_err(|_| format!("bad pendingFrom {from}"))?;
                let to_sq: Square = to.parse().map_err(|_| format!("bad pendingTo {to}"))?;
                let legal = game.pos.legal_moves().into_iter().any(|m| {
                    let (f, t) = move_from_to(&m);
                    f == from_sq && t == to_sq && m.promotion().is_some()
                });
                if legal {
                    game.pending_promotion = Some((from_sq, to_sq));
                }
            }
        }
        game.last_event = "none".to_string();
        Ok(game)
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

impl Game {
    pub fn import_text(
        text: &str,
        vs_ai: bool,
        play_as_white: bool,
        difficulty: u8,
        analysis: bool,
    ) -> Result<Self, String> {
        let mut game = crate::pgn::import_pgn_or_fen(text, vs_ai, play_as_white, difficulty)?;
        if analysis {
            game.mode = GameMode::Analysis;
        }
        Ok(game)
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

    #[test]
    fn save_and_load_round_trip() {
        let mut g = Game::new(true, false, 2);
        assert!(g.play_uci_for_tests("e2e4"));
        assert!(g.play_uci_for_tests("e7e5"));
        let save = g.export_save();
        let loaded = Game::import_save(&save).unwrap();
        let state = loaded.ui_state();
        assert_eq!(state.moves, vec!["e4", "e5"]);
        assert_eq!(state.turn, "WHITE");
        assert!(state.is_board_flipped);
        assert!(state.pgn.contains("1. e4 e5"));
        assert_eq!(state.material, 0);
    }

    #[test]
    fn resign_ends_game() {
        let mut g = Game::new(true, true, 1);
        g.resign();
        let state = g.ui_state();
        assert!(state.game_over);
        assert!(state.game_status.unwrap().contains("resignation"));
        assert_eq!(state.last_event, "resign");
    }

    #[test]
    fn toggle_flip_and_deselect() {
        let mut g = Game::new(false, true, 1);
        g.click(6, 4);
        assert!(g.ui_state().selected.is_some());
        g.deselect();
        assert!(g.ui_state().selected.is_none());
        assert!(!g.ui_state().is_board_flipped);
        g.toggle_flip();
        assert!(g.ui_state().is_board_flipped);
        assert_eq!(g.ui_state().last_event, "none");
    }

    #[test]
    fn capture_sets_last_event() {
        let mut g = Game::new(false, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        assert!(g.play_uci_for_tests("d7d5"));
        assert!(g.play_uci_for_tests("e4d5"));
        assert_eq!(g.ui_state().last_event, "capture");
        assert_eq!(g.ui_state().material, 1);
    }

    #[test]
    fn undo_resign_restores_play() {
        let mut g = Game::new(false, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        g.resign();
        assert!(g.is_game_over());
        g.undo();
        assert!(!g.is_game_over());
        assert_eq!(g.ui_state().turn, "BLACK");
        assert!(g.ui_state().pgn.contains("1. e4 *"));
    }

    #[test]
    fn resume_resign_then_undo_keeps_last_move() {
        let mut g = Game::new(false, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        g.resign();
        let mut loaded = Game::import_save(&g.export_save()).unwrap();
        assert!(loaded.is_game_over());
        loaded.undo();
        assert!(!loaded.is_game_over());
        assert_eq!(loaded.moves, vec!["e4"]);
        assert_eq!(loaded.ui_state().turn, "BLACK");
    }

    #[test]
    fn save_keeps_pending_promotion() {
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
        let mut loaded = Game::import_save(&g.export_save()).unwrap();
        assert!(loaded.pending_promotion.is_some());
        loaded.promote("QUEEN");
        assert_eq!(loaded.ui_state().pieces[0][0].as_ref().unwrap().kind, "QUEEN");
    }

    #[test]
    fn checkmate_pgn_has_result() {
        let mut g = Game::new(false, true, 1);
        for uci in ["e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7"] {
            assert!(g.play_uci_for_tests(uci));
        }
        let pgn = g.ui_state().pgn;
        assert!(pgn.contains("[Result \"1-0\"]"), "{pgn}");
        assert!(pgn.ends_with("1-0"), "{pgn}");
    }

    #[test]
    fn undo_then_redo() {
        let mut g = Game::new(false, true, 1);
        assert!(g.play_uci_for_tests("e2e4"));
        g.undo();
        assert!(g.moves.is_empty());
        assert!(g.ui_state().can_redo);
        g.redo();
        assert_eq!(g.moves, vec!["e4"]);
        assert!(!g.ui_state().can_redo);
    }

    #[test]
    fn two_player_draw_agreement() {
        let mut g = Game::new(false, true, 1);
        g.offer_draw();
        assert!(g.ui_state().draw_offer_pending);
        g.accept_draw();
        assert!(g.is_game_over());
        assert!(g.ui_state().game_status.unwrap().contains("agreement"));
    }

    #[test]
    fn claim_draw_after_long_halfmove() {
        let mut g = Game::new(false, true, 1);
        g.load_fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 80 40").unwrap();
        assert!(g.ui_state().can_claim_draw);
        g.claim_draw();
        assert!(g.is_game_over());
    }

    #[test]
    fn opening_named_after_e4_e5_nf3_nc6_bb5() {
        let mut g = Game::new(false, true, 1);
        for uci in ["e2e4", "e7e5", "g1f3", "b8c6", "f1b5"] {
            assert!(g.play_uci_for_tests(uci));
        }
        let state = g.ui_state();
        assert_eq!(state.opening.as_deref(), Some("Ruy Lopez"));
        assert!(state.opening_moves.iter().any(|c| c.san.contains('a') || c.uci == "a7a6" || !c.san.is_empty()));
    }

    #[test]
    fn analysis_goto_ply_replays_prefix() {
        let mut g = Game::analysis_board();
        assert!(g.play_uci_for_tests("e2e4"));
        assert!(g.play_uci_for_tests("e7e5"));
        g.goto_ply(1);
        assert_eq!(g.moves, vec!["e4"]);
        assert_eq!(g.ui_state().turn, "BLACK");
        assert!(g.ui_state().analysis);
    }

    #[test]
    fn rust_analyze_returns_pv() {
        let g = Game::analysis_board();
        let v: serde_json::Value = serde_json::from_str(&g.rust_analyze(2)).unwrap();
        assert!(v.get("pv").unwrap().as_array().unwrap().len() >= 1);
    }
}
