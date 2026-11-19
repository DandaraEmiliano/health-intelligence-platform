import json

from app.extraction.providers import FakeProvider, ProviderError
from app.extraction.worker import ExtractionWorker
from tests.extraction_fixtures import expected, fixture_ids, llm_answer_from_ground_truth, pdf_bytes, request_for


class FakeSqs:
    """Just enough of the SQS API for the worker, with redelivery counts."""

    def __init__(self) -> None:
        self.queues: dict[str, list[dict]] = {"requests": [], "results": []}
        self.deleted: list[str] = []

    def get_queue_url(self, QueueName: str) -> dict:
        return {"QueueUrl": "requests" if "requests" in QueueName else "results"}

    def put(self, body: str) -> None:
        self.queues["requests"].append({"MessageId": "m1", "ReceiptHandle": "r1", "Body": body, "receives": 0})

    def receive_message(self, QueueUrl: str, **kwargs) -> dict:
        messages = [m for m in self.queues[QueueUrl] if m["ReceiptHandle"] not in self.deleted][:1]
        for m in messages:
            m["receives"] += 1
            m["Attributes"] = {"ApproximateReceiveCount": str(m["receives"])}
        return {"Messages": messages}

    def delete_message(self, QueueUrl: str, ReceiptHandle: str) -> None:
        self.deleted.append(ReceiptHandle)

    def send_message(self, QueueUrl: str, MessageBody: str, MessageAttributes: dict) -> None:
        self.queues[QueueUrl].append({"Body": MessageBody, "MessageAttributes": MessageAttributes})


def worker(sqs: FakeSqs, fixture_id: str, provider) -> ExtractionWorker:
    return ExtractionWorker(
        sqs,
        "document-extraction-requests",
        "document-extraction-results",
        lambda bucket, key: pdf_bytes(fixture_id),
        provider,
        max_receives=3,
    )


def test_publishes_the_result_then_deletes_the_request() -> None:
    fixture_id = next(f for f in fixture_ids() if expected(f)["expected"]["status"] == "SUCCEEDED")
    truth = expected(fixture_id)["expected"]["report"]
    sqs = FakeSqs()
    sqs.put(json.dumps(request_for(fixture_id)))

    published = worker(sqs, fixture_id, FakeProvider(lambda p: llm_answer_from_ground_truth(truth))).poll_once()

    assert published == 1
    result = json.loads(sqs.queues["results"][0]["Body"])
    assert result["status"] == "SUCCEEDED" and result["jobId"] == request_for(fixture_id)["jobId"]
    assert sqs.queues["results"][0]["MessageAttributes"]["eventType"]["StringValue"] == "DocumentExtractionCompleted"
    assert sqs.deleted == ["r1"]


def test_transient_failures_stay_on_the_queue_until_the_last_receive() -> None:
    fixture_id = next(f for f in fixture_ids() if expected(f)["expected"]["status"] == "SUCCEEDED")
    sqs = FakeSqs()
    sqs.put(json.dumps(request_for(fixture_id)))
    w = worker(sqs, fixture_id, FakeProvider(lambda p: ProviderError("LLM_PROVIDER_ERROR", "overloaded", True)))

    assert [w.poll_once(), w.poll_once()] == [0, 0]
    assert sqs.deleted == []
    assert w.poll_once() == 1  # third receive = last attempt
    assert json.loads(sqs.queues["results"][0]["Body"])["failure"]["code"] == "LLM_PROVIDER_ERROR"
    assert sqs.deleted == ["r1"]


def test_invalid_requests_are_dropped() -> None:
    sqs = FakeSqs()
    sqs.put('{"schemaVersion": "9.9"}')

    assert worker(sqs, fixture_ids()[0], FakeProvider(lambda p: None)).poll_once() == 0
    assert sqs.deleted == ["r1"] and sqs.queues["results"] == []
