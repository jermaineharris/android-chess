use shakmaty::zobrist::{Zobrist64, ZobristHash};
use shakmaty::{CastlingMode, Chess, Color, EnPassantMode, Move, Piece, Position, Role, Square};
use std::collections::HashMap;

const PAWN: i32 = 100;
const KNIGHT: i32 = 320;
const BISHOP: i32 = 330;
const ROOK: i32 = 500;
const QUEEN: i32 = 900;
const MATE: i32 = 30_000;

const PAWN_PST: [i32; 64] = [
    0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5,
    10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20,
    -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0,
];

const KNIGHT_PST: [i32; 64] = [
    -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10,
    0, -30, -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10,
    5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
];

const BISHOP_PST: [i32; 64] = [
    -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0,
    -10, -10, 5, 5, 10, 10, 5, 5, -10, -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10,
    -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20,
];

const ROOK_PST: [i32; 64] = [
    0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0,
    0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0,
    5, 5, 0, 0, 0,
];

const QUEEN_PST: [i32; 64] = [
    -20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10, -5,
    0, 5, 5, 5, 5, 0, -5, 0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0,
    -10, -20, -10, -10, -5, -5, -10, -10, -20,
];

const KING_MID: [i32; 64] = [
    -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40,
    -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30,
    -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0,
    10, 30, 20,
];

const KING_END: [i32; 64] = [
    -50, -40, -30, -20, -20, -30, -40, -50, -30, -20, -10, 0, 0, -10, -20, -30, -30, -10, 20, 30, 30,
    20, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10,
    20, 30, 30, 20, -10, -30, -30, -30, 0, 0, 0, 0, -30, -30, -50, -30, -30, -30, -30, -30, -30, -50,
];

const BOOK_LINES: &[&[&str]] = &[
    &["e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6"],
    &["e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6"],
    &["e2e4", "e7e6", "d2d4", "d7d5"],
    &["e2e4", "c7c6", "d2d4", "d7d5"],
    &["d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6"],
    &["d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "d7d5"],
    &["d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5"],
    &["g1f3", "d7d5", "d2d4", "g8f6"],
    &["c2c4", "e7e5"],
];

#[derive(Clone, Copy)]
struct TtEntry {
    depth: i8,
    score: i32,
}

fn role_value(role: Role) -> i32 {
    match role {
        Role::Pawn => PAWN,
        Role::Knight => KNIGHT,
        Role::Bishop => BISHOP,
        Role::Rook => ROOK,
        Role::Queen => QUEEN,
        Role::King => 0,
    }
}

fn pst_index(color: Color, sq: Square) -> usize {
    let i = usize::from(sq);
    if color == Color::White {
        i
    } else {
        i ^ 56
    }
}

fn endgame_weight(pos: &Chess) -> i32 {
    let mut non_pawn = 0;
    for sq in pos.board().occupied() {
        let piece = pos.board().piece_at(sq).unwrap();
        if piece.role != Role::Pawn && piece.role != Role::King {
            non_pawn += role_value(piece.role);
        }
    }
    (3200 - non_pawn).clamp(0, 3200)
}

fn piece_score(piece: Piece, sq: Square, end_w: i32) -> i32 {
    let mut v = role_value(piece.role);
    let idx = pst_index(piece.color, sq);
    v += match piece.role {
        Role::Pawn => PAWN_PST[idx],
        Role::Knight => KNIGHT_PST[idx],
        Role::Bishop => BISHOP_PST[idx],
        Role::Rook => ROOK_PST[idx],
        Role::Queen => QUEEN_PST[idx],
        Role::King => {
            let mid = KING_MID[idx];
            let end = KING_END[idx];
            (mid * (3200 - end_w) + end * end_w) / 3200
        }
    };
    v
}

pub fn evaluate(pos: &Chess) -> i32 {
    let end_w = endgame_weight(pos);
    let mut score = 0;
    for sq in pos.board().occupied() {
        let piece = pos.board().piece_at(sq).unwrap();
        let s = piece_score(piece, sq, end_w);
        score += if piece.color == Color::White { s } else { -s };
    }
    if pos.turn() == Color::White {
        score
    } else {
        -score
    }
}

fn hash_pos(pos: &Chess) -> u64 {
    let Zobrist64(h) = pos.zobrist_hash::<Zobrist64>(EnPassantMode::Legal);
    h
}

fn order_moves(moves: &mut [Move]) {
    moves.sort_by_key(|m| {
        let cap = m.capture().map(role_value).unwrap_or(0);
        let promo = m.promotion().map(role_value).unwrap_or(0);
        -(cap * 10 + promo)
    });
}

fn qsearch(pos: &Chess, mut alpha: i32, beta: i32, ply: i32) -> i32 {
    if pos.is_insufficient_material() {
        return 0;
    }
    let mut moves = pos.legal_moves();
    if moves.is_empty() {
        return if pos.is_check() { -MATE + ply } else { 0 };
    }
    if ply >= 12 {
        return evaluate(pos);
    }

    if !pos.is_check() {
        let stand = evaluate(pos);
        if stand >= beta {
            return stand;
        }
        if stand > alpha {
            alpha = stand;
        }
        moves.retain(|m| m.capture().is_some() || m.promotion().is_some());
        if moves.is_empty() {
            return stand;
        }
    }

    order_moves(&mut moves);
    for m in moves {
        let mut child = pos.clone();
        child.play_unchecked(&m);
        let score = -qsearch(&child, -beta, -alpha, ply + 1);
        if score >= beta {
            return score;
        }
        if score > alpha {
            alpha = score;
        }
    }
    alpha
}

fn negamax(
    pos: &Chess,
    depth: i32,
    mut alpha: i32,
    beta: i32,
    ply: i32,
    tt: &mut HashMap<u64, TtEntry>,
) -> i32 {
    if pos.is_insufficient_material() {
        return 0;
    }
    let mut moves = pos.legal_moves();
    if moves.is_empty() {
        return if pos.is_check() { -MATE + ply } else { 0 };
    }
    if pos.halfmoves() >= 100 {
        return 0;
    }

    let key = hash_pos(pos);
    if let Some(entry) = tt.get(&key) {
        if i32::from(entry.depth) >= depth {
            return entry.score;
        }
    }

    if depth <= 0 {
        return qsearch(pos, alpha, beta, ply);
    }

    order_moves(&mut moves);
    let mut best = i32::MIN / 2;
    for m in moves {
        let mut child = pos.clone();
        child.play_unchecked(&m);
        let score = -negamax(&child, depth - 1, -beta, -alpha, ply + 1, tt);
        if score > best {
            best = score;
        }
        if best > alpha {
            alpha = best;
        }
        if alpha >= beta {
            break;
        }
    }
    tt.insert(
        key,
        TtEntry {
            depth: depth.min(127) as i8,
            score: best,
        },
    );
    best
}

fn score_root_moves(pos: &Chess, depth: u8) -> Vec<(i32, Move)> {
    let mut moves = pos.legal_moves();
    if moves.is_empty() {
        return Vec::new();
    }
    order_moves(&mut moves);
    let mut tt = HashMap::new();
    let depth = depth.max(1) as i32;
    let mut scored = Vec::with_capacity(moves.len());
    let mut alpha = i32::MIN / 2;
    let beta = i32::MAX / 2;
    for m in moves {
        let mut child = pos.clone();
        child.play_unchecked(&m);
        let score = -negamax(&child, depth - 1, -beta, -alpha, 1, &mut tt);
        if score > alpha {
            alpha = score;
        }
        scored.push((score, m));
    }
    scored.sort_by(|a, b| b.0.cmp(&a.0));
    scored
}

fn book_replies(pos: &Chess) -> Vec<Move> {
    let target = hash_pos(pos);
    let mut replies = Vec::new();
    for line in BOOK_LINES {
        let mut cursor = Chess::default();
        for uci in *line {
            if hash_pos(&cursor) == target {
                if let Some(m) = cursor
                    .legal_moves()
                    .into_iter()
                    .find(|m| m.to_uci(CastlingMode::Standard).to_string() == *uci)
                {
                    if !replies.iter().any(|existing| existing == &m) {
                        replies.push(m);
                    }
                }
                break;
            }
            let Some(m) = cursor
                .legal_moves()
                .into_iter()
                .find(|m| m.to_uci(CastlingMode::Standard).to_string() == *uci)
            else {
                break;
            };
            cursor.play_unchecked(&m);
        }
    }
    replies
}

fn pick_index(len: usize, salt: u64) -> usize {
    if len == 0 {
        return 0;
    }
    (salt as usize) % len
}

pub fn choose_move(pos: &Chess, difficulty: u8) -> Option<Move> {
    let legal = pos.legal_moves();
    if legal.is_empty() {
        return None;
    }

    let salt = hash_pos(pos) ^ u64::from(pos.fullmoves().get());
    let book = book_replies(pos);
    let use_book = !book.is_empty()
        && match difficulty {
            0 => salt % 5 != 0,
            1 => salt % 10 != 0,
            _ => true,
        };
    if use_book {
        return Some(book[pick_index(book.len(), salt)].clone());
    }

    let depth = depth_for_difficulty(difficulty);
    let scored = score_root_moves(pos, depth);
    if scored.is_empty() {
        return None;
    }
    if difficulty == 0 {
        let take = (scored.len() / 2).clamp(1, 4.min(scored.len()));
        let pool = &scored[scored.len().saturating_sub(take)..];
        return Some(pool[pick_index(pool.len(), salt >> 3)].1.clone());
    }
    Some(scored[0].1.clone())
}

pub fn best_move(pos: &Chess, depth: u8) -> Option<Move> {
    score_root_moves(pos, depth).into_iter().next().map(|(_, m)| m)
}

/// Root eval (side-to-move) plus a short principal variation in UCI.
pub fn analyze_pv(pos: &Chess, depth: u8) -> (i32, Vec<String>) {
    let scored = score_root_moves(pos, depth);
    let Some((score, best)) = scored.into_iter().next() else {
        return (evaluate(pos), Vec::new());
    };
    let mut pv = vec![best.to_uci(CastlingMode::Standard).to_string()];
    let mut child = pos.clone();
    child.play_unchecked(&best);
    let mut remaining = depth.saturating_sub(1);
    while remaining > 0 {
        let Some(m) = best_move(&child, remaining.max(1)) else {
            break;
        };
        pv.push(m.to_uci(CastlingMode::Standard).to_string());
        child.play_unchecked(&m);
        remaining -= 1;
    }
    (score, pv)
}

pub fn depth_for_difficulty(difficulty: u8) -> u8 {
    match difficulty {
        0 => 1,
        1 => 3,
        2 => 4,
        _ => 5,
    }
}
