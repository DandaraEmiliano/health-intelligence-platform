from app.extraction.evaluate import evaluate
from app.extraction.providers import FakeProvider
from tests.extraction_fixtures import expected, fixture_ids, llm_answer_from_ground_truth


def test_a_perfect_extractor_scores_one_everywhere() -> None:
    by_document = {}

    def answer(prompt):
        for report in by_document.values():
            if report["results"][0]["source"]["excerpt"].split()[0] in prompt.user:
                return llm_answer_from_ground_truth(report)
        raise AssertionError("unknown document")

    ids = [f for f in fixture_ids() if expected(f)["expected"]["status"] == "SUCCEEDED"][:2]
    report = None
    for fixture_id in ids:
        by_document = {fixture_id: expected(fixture_id)["expected"]["report"]}
        report = evaluate(FakeProvider(answer), [fixture_id])
        assert set(report["fieldAccuracy"].values()) == {1.0}, report["mismatches"]
        assert report["mismatches"] == []
