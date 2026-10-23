"""generate.py 검증 (SPEC 9절). 실행: python -m unittest discover tools/datagen"""
import csv
import hashlib
import json
import os
import tempfile
import unittest
from collections import Counter

import generate

ROWS = 100_000
PARTICIPANTS = 20_000
SEED = 42


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def sha256_of_file(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


class GenerateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = cls.tmp.name
        generate.generate(ROWS, PARTICIPANTS, SEED, cls.out)
        cls.persons = read_csv(os.path.join(cls.out, "eligible_person.csv"))
        cls.participants = read_csv(os.path.join(cls.out, "participants.csv"))
        with open(os.path.join(cls.out, "summary.json"), encoding="utf-8") as f:
            cls.summary = json.load(f)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def assertRatio(self, counter, total, expected, tolerance=0.005):
        for key, ratio in expected.items():
            actual = counter[key] / total
            self.assertAlmostEqual(actual, ratio, delta=tolerance, msg=f"{key}: {actual:.4f} vs {ratio}")

    # eligible_person.csv
    def test_person_header_and_count(self):
        with open(os.path.join(self.out, "eligible_person.csv"), encoding="utf-8") as f:
            header = f.readline().strip()
        self.assertEqual(header, "person_id,ci_hash,name,birth_year,region_code,target_group,support_amount")
        self.assertEqual(len(self.persons), ROWS)

    def test_person_id_is_sequential(self):
        self.assertEqual([int(p["person_id"]) for p in self.persons], list(range(1, ROWS + 1)))

    def test_ci_hash_formula(self):
        for p in self.persons[:1000]:
            expected = hashlib.sha256(f"ci-{SEED}-{p['person_id']}".encode()).hexdigest()
            self.assertEqual(p["ci_hash"], expected)

    def test_name(self):
        for p in self.persons[:1000]:
            self.assertEqual(len(p["name"]), 3)
            self.assertIn(p["name"][0], generate.SURNAMES)

    def test_birth_year_range(self):
        years = [int(p["birth_year"]) for p in self.persons]
        self.assertEqual(min(years), 1945)
        self.assertEqual(max(years), 2004)

    def test_region_ratio(self):
        counter = Counter(p["region_code"] for p in self.persons)
        self.assertRatio(counter, ROWS, {code: pct / 100 for code, pct in generate.REGIONS.items()})

    def test_target_group_ratio_and_amount(self):
        counter = Counter(p["target_group"] for p in self.persons)
        self.assertRatio(counter, ROWS, {g: r for g, (r, _) in generate.TARGET_GROUPS.items()})
        for p in self.persons[:1000]:
            self.assertEqual(int(p["support_amount"]), generate.TARGET_GROUPS[p["target_group"]][1])

    # participants.csv
    def test_participant_header_and_count(self):
        with open(os.path.join(self.out, "participants.csv"), encoding="utf-8") as f:
            header = f.readline().strip()
        self.assertEqual(header, "ci_hash,eligible,dup_mode,repeat,bank_code,account_number")
        self.assertEqual(len(self.participants), PARTICIPANTS)

    def test_eligible_participants_are_unique_and_in_list(self):
        person_hashes = {p["ci_hash"] for p in self.persons}
        eligible = [p["ci_hash"] for p in self.participants if p["eligible"] == "true"]
        self.assertEqual(len(eligible), int(PARTICIPANTS * 0.9))
        self.assertEqual(len(set(eligible)), len(eligible))
        self.assertTrue(set(eligible) <= person_hashes)

    def test_non_eligible_participants(self):
        person_hashes = {p["ci_hash"] for p in self.persons}
        none = [p["ci_hash"] for p in self.participants if p["eligible"] == "false"]
        self.assertEqual(len(none), PARTICIPANTS - int(PARTICIPANTS * 0.9))
        self.assertFalse(set(none) & person_hashes)
        expected = {hashlib.sha256(f"none-{SEED}-{i}".encode()).hexdigest() for i in range(1, len(none) + 1)}
        self.assertEqual(set(none), expected)

    def test_eligible_and_none_are_mixed(self):
        # 앞쪽 1000명 안에도 명단 밖 사람이 섞여 있어야 k6가 순서대로 읽어도 비율이 유지된다
        head = Counter(p["eligible"] for p in self.participants[:1000])
        self.assertGreater(head["false"], 50)

    def test_dup_mode_and_repeat(self):
        counter = Counter(p["dup_mode"] for p in self.participants)
        self.assertRatio(counter, PARTICIPANTS, {"none": 0.95, "same_key": 0.025, "diff_key": 0.025})
        for p in self.participants:
            if p["dup_mode"] == "none":
                self.assertEqual(p["repeat"], "1")
            else:
                self.assertIn(p["repeat"], ("2", "3"))
        dup = [p["repeat"] for p in self.participants if p["dup_mode"] != "none"]
        self.assertAlmostEqual(dup.count("2") / len(dup), 0.5, delta=0.05)

    def test_bank_and_account(self):
        banks = Counter(p["bank_code"] for p in self.participants)
        self.assertEqual(set(banks), set(generate.BANK_CODES))
        failing = sum(p["account_number"].endswith("0000") for p in self.participants)
        self.assertAlmostEqual(failing / PARTICIPANTS, 0.01, delta=0.003)
        for p in self.participants[:1000]:
            self.assertRegex(p["account_number"], r"^\d{3}-\d{4}-\d{6}$")

    # summary.json
    def test_summary_demand_and_budget(self):
        amount_by_hash = {p["ci_hash"]: int(p["support_amount"]) for p in self.persons}
        demand = sum(amount_by_hash[p["ci_hash"]] for p in self.participants if p["eligible"] == "true")
        self.assertEqual(self.summary["participant_demand_amount"], demand)
        self.assertEqual(self.summary["recommended_total_budget"], demand * 3 // 10 // 1_000_000 * 1_000_000)
        self.assertEqual(sum(self.summary["persons_by_target_group"].values()), ROWS)
        self.assertEqual(sum(self.summary["persons_by_region"].values()), ROWS)

    # 재현성
    def test_same_seed_same_files_and_different_seed_differs(self):
        names = ("eligible_person.csv", "participants.csv", "summary.json")
        with tempfile.TemporaryDirectory() as again, tempfile.TemporaryDirectory() as other:
            generate.generate(ROWS, PARTICIPANTS, SEED, again)
            generate.generate(ROWS, PARTICIPANTS, SEED + 1, other)
            for name in names:
                original = sha256_of_file(os.path.join(self.out, name))
                self.assertEqual(original, sha256_of_file(os.path.join(again, name)), name)
                self.assertNotEqual(original, sha256_of_file(os.path.join(other, name)), name)


if __name__ == "__main__":
    unittest.main()
