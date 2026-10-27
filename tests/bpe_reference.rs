use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Merge {
    left: i32,
    right: i32,
    result: i32,
    rank: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Token {
    id: i32,
    start: usize,
    end: usize,
}

fn encode_utf8_bytes(data: &[i32], merges: &[Merge]) -> Result<Vec<Token>, String> {
    let mut seen_pairs = HashSet::new();
    let mut seen_ranks = HashSet::new();
    let mut producer_ranks = HashMap::new();
    let mut table = HashMap::new();

    for merge in merges {
        if merge.left < 0 || merge.right < 0 {
            return Err("negative token".into());
        }
        if merge.result < 256 {
            return Err("result token must be >= 256".into());
        }
        if merge.rank < 0 {
            return Err("negative rank".into());
        }
        if !seen_pairs.insert((merge.left, merge.right)) {
            return Err("duplicate pair".into());
        }
        if !seen_ranks.insert(merge.rank) {
            return Err("duplicate rank".into());
        }
        if producer_ranks.insert(merge.result, merge.rank).is_some() {
            return Err("duplicate result token".into());
        }
        table.insert((merge.left, merge.right), (merge.rank, merge.result));
    }

    for merge in merges {
        for operand in [merge.left, merge.right] {
            if operand < 256 {
                continue;
            }
            let Some(&producer_rank) = producer_ranks.get(&operand) else {
                return Err("composite token has no producer".into());
            };
            if producer_rank >= merge.rank {
                return Err("composite producer must have lower rank".into());
            }
        }
    }

    for byte in data {
        if !(0..=255).contains(byte) {
            return Err("invalid byte".into());
        }
    }

    let mut tokens = Vec::with_capacity(data.len());
    for (offset, byte) in data.iter().copied().enumerate() {
        tokens.push(Token {
            id: byte,
            start: offset,
            end: offset + 1,
        });
    }

    loop {
        let mut best: Option<(i32, usize, i32)> = None;

        for position in 0..tokens.len().saturating_sub(1) {
            let pair = (tokens[position].id, tokens[position + 1].id);
            let Some(&(rank, result)) = table.get(&pair) else {
                continue;
            };

            let candidate = (rank, position, result);
            if best.map_or(true, |current| {
                (candidate.0, candidate.1) < (current.0, current.1)
            }) {
                best = Some(candidate);
            }
        }

        let Some((_, position, result)) = best else {
            break;
        };

        let merged = Token {
            id: result,
            start: tokens[position].start,
            end: tokens[position + 1].end,
        };
        tokens.splice(position..=position + 1, [merged]);
    }

    Ok(tokens)
}

#[test]
fn ascii_chain() {
    let merges = [
        Merge { left: b'a' as i32, right: b'b' as i32, result: 256, rank: 0 },
        Merge { left: 256, right: b'c' as i32, result: 257, rank: 1 },
    ];
    assert_eq!(
        encode_utf8_bytes(&[97, 98, 99], &merges).unwrap(),
        vec![Token { id: 257, start: 0, end: 3 }]
    );
}

#[test]
fn global_rank_beats_leftmost_position() {
    let merges = [
        Merge { left: 97, right: 98, result: 256, rank: 1 },
        Merge { left: 98, right: 97, result: 257, rank: 0 },
    ];
    assert_eq!(
        encode_utf8_bytes(&[97, 98, 97], &merges).unwrap(),
        vec![
            Token { id: 97, start: 0, end: 1 },
            Token { id: 257, start: 1, end: 3 },
        ]
    );
}

#[test]
fn utf8_two_byte_span() {
    let bytes: Vec<i32> = "é".as_bytes().iter().map(|b| *b as i32).collect();
    assert_eq!(bytes, vec![195, 169]);
    let merges = [Merge { left: 195, right: 169, result: 300, rank: 0 }];
    assert_eq!(
        encode_utf8_bytes(&bytes, &merges).unwrap(),
        vec![Token { id: 300, start: 0, end: 2 }]
    );
}

#[test]
fn utf8_emoji_span() {
    let bytes: Vec<i32> = "🙂".as_bytes().iter().map(|b| *b as i32).collect();
    assert_eq!(bytes, vec![240, 159, 153, 130]);
    let merges = [
        Merge { left: 240, right: 159, result: 300, rank: 0 },
        Merge { left: 300, right: 153, result: 301, rank: 1 },
        Merge { left: 301, right: 130, result: 302, rank: 2 },
    ];
    assert_eq!(
        encode_utf8_bytes(&bytes, &merges).unwrap(),
        vec![Token { id: 302, start: 0, end: 4 }]
    );
}

#[test]
fn empty_input() {
    assert_eq!(encode_utf8_bytes(&[], &[]).unwrap(), Vec::<Token>::new());
}

#[test]
fn rejects_invalid_byte() {
    assert!(encode_utf8_bytes(&[256], &[]).is_err());
}

#[test]
fn rejects_duplicate_pair() {
    let merges = [
        Merge { left: 1, right: 2, result: 256, rank: 0 },
        Merge { left: 1, right: 2, result: 257, rank: 1 },
    ];
    assert!(encode_utf8_bytes(&[1, 2], &merges).is_err());
}

#[test]
fn rejects_duplicate_rank() {
    let merges = [
        Merge { left: 1, right: 2, result: 256, rank: 0 },
        Merge { left: 2, right: 3, result: 257, rank: 0 },
    ];
    assert!(encode_utf8_bytes(&[1, 2, 3], &merges).is_err());
}


#[test]
fn rejects_duplicate_result_token() {
    let merges = [
        Merge { left: 1, right: 2, result: 256, rank: 0 },
        Merge { left: 2, right: 3, result: 256, rank: 1 },
    ];
    assert!(encode_utf8_bytes(&[1, 2, 3], &merges).is_err());
}

#[test]
fn rejects_missing_composite_producer() {
    let merges = [
        Merge { left: 300, right: 97, result: 301, rank: 1 },
    ];
    assert!(encode_utf8_bytes(&[97], &merges).is_err());
}

#[test]
fn rejects_forward_rank_dependency() {
    let merges = [
        Merge { left: 97, right: 98, result: 256, rank: 2 },
        Merge { left: 256, right: 99, result: 257, rank: 1 },
    ];
    assert!(encode_utf8_bytes(&[97, 98, 99], &merges).is_err());
}
