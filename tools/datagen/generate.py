"""가상 데이터 생성 (SPEC 9절).

python tools/datagen/generate.py --rows 1000000 --participants 120000 --seed 42 --out data/
"""
import argparse
import hashlib
import json
import os
import time

import numpy as np

SURNAMES = list("김이박최정강조윤장임한오서신권황안송류전")
GIVEN_SYLLABLES = list("민서준지현우예도하윤수진영은성재호연원경태혜동승미정유주희상나선형소다인아규석빈")

# target_group: (비율, 지원 금액)
TARGET_GROUPS = {
    "TRUCK": (0.30, 300_000),
    "FARM_FISH": (0.25, 150_000),
    "LOW_INCOME": (0.25, 100_000),
    "TAXI_BUS": (0.20, 200_000),
}

# 시도 코드: 비율(%)
REGIONS = {
    "11": 18.5, "26": 6.4, "27": 4.5, "28": 6.0, "29": 2.7, "30": 2.8, "31": 2.1, "36": 0.8,
    "41": 26.6, "51": 2.9, "43": 3.1, "44": 4.1, "52": 3.4, "46": 3.5, "47": 4.9, "48": 6.4, "50": 1.3,
}

BANK_CODES = ["004", "088", "020", "081", "011", "003", "023", "027", "090", "092"]

ELIGIBLE_RATIO = 0.9
DUP_MODES = ["none", "same_key", "diff_key"]
DUP_MODE_P = [0.95, 0.025, 0.025]
ACCOUNT_FAIL_RATIO = 0.01


def sha256_hex(text):
    return hashlib.sha256(text.encode()).hexdigest()


def write_csv(path, header, lines):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(header + "\n")
        f.write("\n".join(lines))
        f.write("\n")


def generate_persons(rng, rows, seed):
    surnames = rng.integers(0, len(SURNAMES), rows)
    given = rng.integers(0, len(GIVEN_SYLLABLES), (rows, 2))
    birth_years = rng.integers(1945, 2005, rows)  # 2004 포함

    region_codes = list(REGIONS)
    region_p = np.array(list(REGIONS.values())) / 100
    regions = rng.choice(len(region_codes), rows, p=region_p / region_p.sum())

    groups = list(TARGET_GROUPS)
    groups_idx = rng.choice(len(groups), rows, p=[TARGET_GROUPS[g][0] for g in groups])

    ci_hashes = [sha256_hex(f"ci-{seed}-{pid}") for pid in range(1, rows + 1)]
    amounts = [TARGET_GROUPS[g][1] for g in groups]
    lines = [
        f"{pid},{ci_hashes[pid - 1]},"
        f"{SURNAMES[s]}{GIVEN_SYLLABLES[g1]}{GIVEN_SYLLABLES[g2]},"
        f"{y},{region_codes[r]},{groups[t]},{amounts[t]}"
        for pid, s, (g1, g2), y, r, t in zip(
            range(1, rows + 1), surnames.tolist(), given.tolist(), birth_years.tolist(),
            regions.tolist(), groups_idx.tolist())
    ]
    return lines, ci_hashes, groups_idx, regions, region_codes, groups


def generate_participants(rng, rows, participants, seed, ci_hashes):
    n_eligible = int(participants * ELIGIBLE_RATIO)
    n_none = participants - n_eligible

    eligible_idx = rng.choice(rows, n_eligible, replace=False)  # 명단에서 중복 없이
    hashes = [ci_hashes[i] for i in eligible_idx.tolist()]
    hashes += [sha256_hex(f"none-{seed}-{i}") for i in range(1, n_none + 1)]
    eligible = [True] * n_eligible + [False] * n_none

    order = rng.permutation(participants)  # 명단 밖 사람이 고르게 섞이도록
    hashes = [hashes[i] for i in order.tolist()]
    eligible = [eligible[i] for i in order.tolist()]

    dup = rng.choice(len(DUP_MODES), participants, p=DUP_MODE_P)
    repeat = np.where(dup == 0, 1, rng.integers(2, 4, participants))
    banks = rng.integers(0, len(BANK_CODES), participants)

    # 계좌번호 XXX-XXXX-XXXXXX. 끝 4자리가 0000이면 mock 계좌 검증 실패 (SPEC 7절)
    head = rng.integers(0, 1000, participants)
    mid = rng.integers(0, 10000, participants)
    tail_hi = rng.integers(0, 100, participants)
    tail_lo = rng.integers(1, 10000, participants)  # 실패 대상이 아니면 0000이 나오지 않게
    tail_lo[rng.random(participants) < ACCOUNT_FAIL_RATIO] = 0

    lines = [
        f"{h},{'true' if e else 'false'},{DUP_MODES[d]},{r},{BANK_CODES[b]},{a:03d}-{m:04d}-{hi:02d}{lo:04d}"
        for h, e, d, r, b, a, m, hi, lo in zip(
            hashes, eligible, dup.tolist(), repeat.tolist(), banks.tolist(),
            head.tolist(), mid.tolist(), tail_hi.tolist(), tail_lo.tolist())
    ]
    return lines, eligible_idx, dup


def generate(rows, participants, seed, out):
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(seed)

    person_lines, ci_hashes, groups_idx, regions, region_codes, groups = generate_persons(rng, rows, seed)
    write_csv(os.path.join(out, "eligible_person.csv"),
              "person_id,ci_hash,name,birth_year,region_code,target_group,support_amount", person_lines)
    del person_lines

    participant_lines, eligible_idx, dup = generate_participants(rng, rows, participants, seed, ci_hashes)
    write_csv(os.path.join(out, "participants.csv"),
              "ci_hash,eligible,dup_mode,repeat,bank_code,account_number", participant_lines)

    amounts = np.array([TARGET_GROUPS[g][1] for g in groups], dtype=np.int64)
    list_amount = int(amounts[groups_idx].sum())
    demand = int(amounts[groups_idx[eligible_idx]].sum())  # 신청자 중 명단에 있는 사람 기준 (SPEC 9.3)

    summary = {
        "seed": seed,
        "rows": rows,
        "participants": participants,
        "participants_eligible": len(eligible_idx),
        "participants_not_eligible": participants - len(eligible_idx),
        "participants_by_dup_mode": {m: int((dup == i).sum()) for i, m in enumerate(DUP_MODES)},
        "persons_by_target_group": {g: int((groups_idx == i).sum()) for i, g in enumerate(groups)},
        "persons_by_region": {c: int((regions == i).sum()) for i, c in enumerate(region_codes)},
        "list_total_amount": list_amount,
        "participant_demand_amount": demand,
        "recommended_total_budget": demand * 3 // 10 // 1_000_000 * 1_000_000,
    }
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description="가상 데이터 생성 (SPEC 9절)")
    parser.add_argument("--rows", type=int, default=1_000_000)
    parser.add_argument("--participants", type=int, default=120_000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", default="data/")
    args = parser.parse_args()

    started = time.perf_counter()
    summary = generate(args.rows, args.participants, args.seed, args.out)
    elapsed = time.perf_counter() - started
    print(f"생성 완료: {args.out} ({elapsed:.1f}초)")
    print(f"  명단 {summary['rows']:,}명 / 신청자 {summary['participants']:,}명")
    print(f"  수요 {summary['participant_demand_amount']:,}원 / 권장 예산 {summary['recommended_total_budget']:,}원")


if __name__ == "__main__":
    main()
