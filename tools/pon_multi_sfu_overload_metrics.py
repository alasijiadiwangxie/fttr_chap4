"""Windowed multi-SFU DBA metrics reconstructed in one pass over Java stdout."""

from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass, field
from decimal import Decimal
from hashlib import sha256
from math import sqrt
from struct import pack
from typing import Iterable

from pon_execution_trace import fields
from pon_experiment_io import require


PHASES = ("fixed", "assured", "non_assured", "best_effort")
COUNTS = ("generated", "enqueued", "delivered_at_cutoff", "delivered", "dropped_at_cutoff", "dropped", "final_delivered",
          "final_dropped", "final_pending", "pending_at_cutoff", "fifo_pending_at_cutoff", "final_fifo")
TIMES = ("fifo", "e2e", "report_age", "grant_gap", "actual_service_gap", "scope_grant_gap", "scope_actual_service_gap")
METRIC_COLUMNS = (
    "scenario_id", "policy", "variant", "seed", "load", "scope", "sfu_id", "allocation_id", "tcont_type",
    "traffic_end_ns", "decision_end_ns", "observation_start_ns", "observation_end_ns", "simulation_end_ns",
    "scenario", "sfu_count", "t4_mbps", "configured_offered_mbps", "offered_mbps", "throughput_mbps",
    "first_half_throughput_mbps", "second_half_throughput_mbps", "offered_to_capacity_ratio",
) + tuple(f"{count}_{unit}" for count in COUNTS for unit in ("packets", "bytes")) + (
    "actual_drop_fraction", "overflow_status", "overflow_dropped_packets", "drain_delivered_packets",
    "avg_queue_packets", "avg_queue_bytes", "peak_queue_packets", "peak_queue_bytes",
    "fifo_max_censored_age_ns", "final_pending_fraction", "data_busy_ns", "data_reserved_ns", "report_busy_ns",
    "unused_reservation_ns", "physical_busy_ns", "reserved_busy_ns", "resource_utilization", "reservation_utilization",
    "observed_data_tx_packets", "observed_data_tx_bytes", "reserved_data_grants", "used_data_grants", "empty_data_grants",
    "budget_bytes", "used_budget_bytes", "unused_budget_bytes", "unused_budget_fraction", "report_slots", "report_rx_count",
    "report_samples", "report_known_samples", "report_unknown_samples", "report_known_fraction",
    "configured_fixed_sla_mbps", "configured_assured_sla_mbps", "configured_sla_mbps",
    "configured_fixed_si_frames", "configured_fixed_ab_bytes", "configured_assured_si_frames", "configured_assured_ab_bytes",
    "configured_non_assured_si_frames", "configured_non_assured_ab_bytes", "configured_best_effort_si_frames",
    "configured_best_effort_ab_bytes",
    "reference_due_bytes", "reference_qualified_bytes", "reference_grant_covered_bytes", "reference_actual_covered_bytes",
    "reference_grant_coverage", "reference_actual_coverage", "reference_grant_violations", "reference_actual_violations",
    "si_violations", "phase_priority_order_violations", "max_known_backlog_no_service_frames", "rr_outstanding_skip_frames",
) + tuple(f"{kind}_{column}" for kind in TIMES for column in ("sample_count", "mean_ns", "p95_ns", "p99_ns", "p999_ns", "max_ns", "jitter_sd_ns")) + tuple(
    f"{phase}_{column}" for phase in PHASES for column in (
        "configured_due_bytes", "qualified_bytes", "authorized_bytes", "attributed_actual_bytes",
        "authorization_shortfalls", "actual_shortfalls", "unknown_due_opportunities",
    )
)

PACKET_COLUMNS = (
    "scenario_id", "policy", "seed", "packet_id", "sfu_id", "allocation_id", "tcont_type", "access_category",
    "source_sta_id", "packet_bytes", "generation_ns", "pon_enqueue_ns", "pon_load_ns", "pon_tx_start_ns",
    "pon_tx_end_ns", "mfu_delivery_ns", "drop_ns", "fifo_wait_ns", "e2e_delay_ns", "status_at_cutoff", "final_status",
)
EVENTS = frozenset((
    "ARRIVAL", "TCONT_ENQUEUE", "TCONT_REPORT_RX", "PON_DBA_OBJECT_STATE", "PON_GIANT_DECISION",
    "PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX", "PON_DBA_FILL", "PON_DBA_DATA_START", "PON_DBA_DATA_END",
    "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX", "PON_POLL_BWMAP_TX", "PON_POLL_BWMAP_RX", "PON_POLL_TX_START",
    "PON_POLL_TX_END", "DELIVERED", "WIFI_COLLISION_DROP", "WIFI_RETRY_LIMIT_DROP",
))


def _ns(us: str | int | float) -> int:
    number = Decimal(str(us)) * 1000
    require(number == int(number), "Timestamp is finer than the simulator ns clock")
    return int(number)


def _clock(row: dict) -> int:
    if "time_ns" in row:
        return int(row["time_ns"])
    whole, _, fraction = row["t_us"].partition(".")
    require(len(fraction) <= 3, "Java timestamp exceeds 3 fractional microseconds digits")
    return int(whole) * 1000 + int(fraction.ljust(3, "0"))


def _ratio(top: int, bottom: int) -> float | None:
    return top / bottom if bottom else None


def _serialized(size: int, rate: int) -> int:
    return (size * 8000 + rate - 1) // rate


@dataclass(slots=True)
class Profile:
    sfu: int
    kind: int
    categories: tuple[str, ...]
    intervals: tuple[int, ...]
    amounts: tuple[int, ...]
    scopes: tuple[tuple, ...]


def _profiles(config: dict) -> tuple[dict[int, Profile], dict[tuple[int, str], int]]:
    profiles, routes = {}, {}
    root = "fiber.uplink.tcont.alloc."
    for aid in map(int, str(config[root + "ids"]).split(",")):
        prefix = f"{root}{aid}."
        sfu = int(config[prefix + "sfu.id"])
        kind = int(config[prefix + "type"])
        categories = tuple(text.strip() for text in str(config[prefix + "classes"]).split(","))
        interval_keys = ("fixed", "max", "min", "min" if kind == 4 else "be")
        amount_keys = ("fixed", "min", "sur", "sur" if kind == 4 else "be")
        active = (kind in (1, 5), kind in (2, 3, 5), kind in (3, 5), kind in (4, 5))
        intervals = tuple(int(config.get(f"{prefix}si.{key}.frames", 0)) if used else 0
                          for key, used in zip(interval_keys, active))
        amounts = tuple(int(config.get(f"{prefix}ab.{key}.bytes", 0)) if used else 0
                        for key, used in zip(amount_keys, active))
        keys = (("link", None, None, None), ("sfu", sfu, None, None), ("type", None, None, kind), ("alloc", sfu, aid, kind))
        profiles[aid] = Profile(sfu, kind, categories, intervals, amounts, keys)
        for category in categories:
            require((sfu, category) not in routes, "Duplicate access-category route in experiment")
            routes[sfu, category] = aid
    return profiles, routes


@dataclass(slots=True)
class Packet:
    size: int
    source: int
    category: str
    aid: int
    generation: int
    enqueue: int = -1
    load: int = -1
    start: int = -1
    end: int = -1
    delivered: int = -1
    dropped: int = -1


@dataclass(slots=True)
class Grant:
    aid: int
    frame: int
    start: int
    end: int
    budget: int
    fixed: int
    received: bool = False
    selected: list[int] | None = None
    selected_bytes: int = 0
    used_bytes: int = 0
    tx_index: int = 0
    slot_ended: bool = False
    settled: bool = False


@dataclass(slots=True)
class Poll:
    aid: int
    frame: int
    start: int
    end: int
    received: bool = False
    started: bool = False
    ended: bool = False
    reported: bool = False


@dataclass(slots=True)
class Decision:
    known: int
    age: int
    outstanding: bool
    report_frame: int
    phase_row: dict | None = None
    grant_id: str | None = None


@dataclass(slots=True)
class Allocation:
    fifo: deque[int] = field(default_factory=deque)
    report: tuple[int, int, int] | None = None  # snapshot time, frame id, pending bytes
    inflight: set[str] = field(default_factory=set)
    report_grants: deque[Grant] = field(default_factory=deque)
    last_grant_frame: int | None = None
    last_actual_frame: int | None = None
    last_phase_after: tuple[int, ...] | None = None
    no_service_streak: int = 0


@dataclass(slots=True)
class Group:
    values: defaultdict = field(default_factory=lambda: defaultdict(int))
    samples: defaultdict = field(default_factory=lambda: defaultdict(list))
    queue_last_ns: int = 0
    queue_packets: int = 0
    queue_bytes: int = 0
    last_grant_frame: int | None = None
    last_actual_frame: int | None = None

    def queue_change(self, now: int, begin: int, stop: int, packets: int = 0, size: int = 0) -> None:
        elapsed = min(now, stop) - max(self.queue_last_ns, begin)
        if elapsed > 0:
            values = self.values
            values["queue_area_packets_ns"] += self.queue_packets * elapsed
            values["queue_area_bytes_ns"] += self.queue_bytes * elapsed
            values["peak_queue_packets"] = max(values["peak_queue_packets"], self.queue_packets)
            values["peak_queue_bytes"] = max(values["peak_queue_bytes"], self.queue_bytes)
        self.queue_last_ns = now
        self.queue_packets += packets
        self.queue_bytes += size
        require(self.queue_packets >= 0 and self.queue_bytes >= 0, "Observed FIFO occupancy became negative")


@dataclass
class OverloadRun:
    metrics: list[dict]
    workload_digest: str
    workload_count: int
    protected_input_digest: str
    packet_rows: list[dict]
    frame_rows: list[dict]
    grant_rows: list[dict]


def _distribution(samples: list[int], prefix: str) -> dict:
    n = len(samples)
    if not n:
        return {f"{prefix}_sample_count": 0}
    ordered = sorted(samples)
    total = sum(ordered)
    # Difference of integer sums keeps precision when values cluster.
    variance = (n * sum(value * value for value in ordered) - total ** 2) / (n * (n - 1)) if n > 1 else None
    return {
        f"{prefix}_sample_count": n, f"{prefix}_mean_ns": total / n,
        f"{prefix}_p95_ns": ordered[n - n // 20 - 1], f"{prefix}_p99_ns": ordered[n - n // 100 - 1],
        f"{prefix}_p999_ns": ordered[n - n // 1000 - 1], f"{prefix}_max_ns": ordered[-1],
        f"{prefix}_jitter_sd_ns": sqrt(variance) if variance is not None else None,
    }


class Trace:
    """Store only compact packet state, active frame decisions, and derived samples."""

    def __init__(self, case, keep_grant_trace: bool):
        self.case = case
        self.keep_grant_trace = keep_grant_trace
        config = case.config
        self.profiles, self.routes = _profiles(config)
        self.sta_owners = {int(key.split(".")[1]): int(value) for key, value in config.items()
                           if key.startswith("sta.") and key.endswith(".sfu.id")}
        self.allocations = {aid: Allocation() for aid in self.profiles}
        self.groups = {scope: Group() for profile in self.profiles.values() for scope in profile.scopes}
        self.group_lookup = {aid: tuple(self.groups[key] for key in profile.scopes) for aid, profile in self.profiles.items()}
        self.traffic_end = _ns(case.traffic_end_us)
        self.stop = _ns(case.decision_end_us)
        self.begin = _ns(getattr(case, "observation_start_us", 0))
        self.cutoff = _ns(getattr(case, "observation_end_us", case.traffic_end_us))
        self.midpoint = (self.begin + self.cutoff) // 2
        require(0 <= self.begin < self.cutoff <= self.stop and self.traffic_end <= self.stop, "Invalid observation window")
        self.frame_ns = _ns(config["fiber.uplink.frame.period.us"])
        self.up_prop = _ns(config["fiber.uplink.propagation.us"])
        self.down_prop = _ns(config["fiber.downlink.propagation.us"])
        self.rate = int(config["fiber.uplink.rate.mbps"])
        self.poll_ns = _serialized(int(config["fiber.uplink.poll.bytes"]), self.rate)
        self.complete = case.policy == "giant-complete"
        require(self.complete or case.policy == "rr-multi", "Expected a real, multi-grant RR or GIANT complete mode")
        self.packets: dict[int, Packet] = {}
        self.grants: dict[str, Grant] = {}
        self.polls: dict[str, Poll] = {}
        self.poll_frames: dict[tuple[int, int], str] = {}
        self.frames: dict[int, dict[int, Decision]] = defaultdict(dict)
        self.reservations: dict[int, list[tuple[int, int]]] = defaultdict(list)
        self.first_frame = None
        self.last_measured_frame = 1
        self.word = sha256()
        self.protected = {}
        self.active: tuple[str, int | str] | None = None
        self.last_ns = -1
        self.summary: dict[str, dict] = {}
        self.generator_packets = self.generator_bytes = 0
        self.simulation_end = -1
        self.frame_rows: list[dict] = []
        self.grant_rows: list[dict] = []

    def overlap(self, start: int, end: int) -> int:
        return max(0, min(end, self.cutoff) - max(start, self.begin))

    def add(self, aid: int, values: dict) -> None:
        for group in self.group_lookup[aid]:
            for key, value in values.items():
                group.values[key] += value

    def count(self, aid: int, key: str, size: int) -> None:
        self.add(aid, {f"{key}_packets": 1, f"{key}_bytes": size})

    def sample(self, aid: int, key: str, value: int) -> None:
        require(value >= 0, f"Negative {key} sample")
        for group in self.group_lookup[aid]:
            group.samples[key].append(value)

    def update_queue(self, aid: int, now: int, packets: int, size: int) -> None:
        for group in self.group_lookup[aid]:
            group.queue_change(now, self.begin, self.cutoff, packets, size)

    def arrival(self, row: dict, now: int) -> None:
        pid, size, source = (int(row[key]) for key in ("packet_id", "bytes", "source_sta_id"))
        sfu = self.sta_owners[source]
        require("sfu_id" not in row or int(row["sfu_id"]) == sfu, "Input STA and SFU route disagree")
        category = row["type"]
        aid = self.routes[sfu, category]
        require(pid not in self.packets and 0 <= now < self.traffic_end and size > 0, "Invalid input packet")
        self.packets[pid] = Packet(size, source, category, aid, now)
        encoded = pack("<qqqq", now, size, source, sfu) + category.encode("ascii")
        self.word.update(pack("<q", pid) + encoded)
        if category != "BK":
            key = (source, category)
            if key not in self.protected:
                self.protected[key] = sha256()
            self.protected[key].update(encoded)

    def packet_event(self, event: str, row: dict, now: int) -> None:
        pid = int(row["packet_id"])
        packet = self.packets[pid]
        aid, size = packet.aid, packet.size
        require(self.profiles[aid].sfu == int(row["sfu_id"]), "Packet routed through a wrong SFU")
        if event == "TCONT_ENQUEUE":
            require(packet.enqueue < 0 and packet.dropped < 0 and now >= packet.generation
                    and int(row["allocation_id"]) == aid and int(row["packet_bytes"]) == size,
                    "Invalid optical FIFO enqueue")
            packet.enqueue = now
            self.allocations[aid].fifo.append(pid)
            self.count(aid, "enqueued", size)
            self.update_queue(aid, now, 1, size)
        elif event == "DELIVERED":
            require(packet.delivered < 0 and packet.end >= 0 and now == packet.end + self.up_prop
                    and size == int(row["bytes"]) and packet.source == int(row["source_sta_id"]),
                    "MFU delivery disagrees with DATA timing or route")
            packet.delivered = now
            if self.begin <= now < self.cutoff:
                self.count(aid, "delivered", size)
                self.sample(aid, "e2e", now - packet.generation)
                half = "first_half" if now < self.midpoint else "second_half"
                self.add(aid, {f"{half}_delivered_bytes": size})
        else:
            require(packet.enqueue < 0 and packet.dropped < 0 and packet.delivered < 0, "Duplicate terminal loss")
            packet.dropped = now
            if self.begin <= now < self.cutoff:
                self.count(aid, "dropped", size)

    def decision(self, aid: int, row: dict, now: int) -> None:
        obj = self.allocations[aid]
        frame = int(row["target_frame_id"])
        require(frame >= 1 and now == (frame - 1) * self.frame_ns and now < self.stop and aid not in self.frames[frame],
                "Duplicate or unaligned DBA decision")
        self.first_frame = frame if self.first_frame is None else self.first_frame
        known, age, report_frame = (int(row[key]) for key in ("known_demand_bytes", "report_age_ns", "report_frame_id"))
        outstanding = row["budget_outstanding"] == "true"
        require(outstanding == bool(obj.inflight), "Logged outstanding indicator disagrees with real budgets")
        if obj.report is None:
            require((row["state"], known, age, report_frame) == ("UNKNOWN", -1, -1, -1), "Unknown REPORT state was lost")
        else:
            snapshot, expected_frame, pending = obj.report
            while obj.report_grants and obj.report_grants[0].start <= snapshot:
                obj.report_grants.popleft()
            debit = sum(grant.used_bytes if grant.settled else grant.budget for grant in obj.report_grants)
            require((row["state"], known, age, report_frame) == ("KNOWN", max(0, pending - debit), now - snapshot, expected_frame),
                    "MFU known demand disagrees with REPORT and valid reservations")
        self.frames[frame][aid] = Decision(known, age, outstanding, report_frame)

    def poll_event(self, event: str, aid: int, row: dict, now: int) -> None:
        poll_id = row["poll_id"]
        start, end, frame = (int(row[key]) for key in ("tx_start_ns", "tx_end_ns", "target_frame_id"))
        if event == "PON_POLL_BWMAP_TX":
            require(poll_id not in self.polls and (aid, frame) not in self.poll_frames and now < self.stop
                    and now == (frame - 1) * self.frame_ns and start == frame * self.frame_ns and end - start == self.poll_ns,
                    "Invalid REPORT-only reservation")
            self.poll_frames[aid, frame] = poll_id
            self.polls[poll_id] = Poll(aid, frame, start, end)
            self.reservations[frame].append((start, end))
            return
        poll = self.polls[poll_id]
        require((poll.aid, poll.frame, poll.start, poll.end) == (aid, frame, start, end), "REPORT-only identity changed")
        if event == "PON_POLL_BWMAP_RX":
            require(not poll.received and now == (frame - 1) * self.frame_ns + self.down_prop and now < start,
                    "Invalid REPORT-only BWmap reception")
            poll.received = True
        elif event == "PON_POLL_TX_START":
            require(poll.received and not poll.started and now == start and self.active is None, "Overlapping or early poll TX")
            poll.started = True
            self.active = ("REPORT", poll_id)
        else:
            require(self.active == ("REPORT", poll_id) and now == end and not poll.ended, "Invalid poll TX end")
            poll.ended = True
            self.active = None
            self.add(aid, {"report_busy_ns": self.overlap(start, end), "report_slots": int(self.begin <= start and end <= self.cutoff)})

    def report(self, aid: int, row: dict, now: int) -> None:
        snapshot, frame, pending = (int(row[key]) for key in ("snapshot_time_ns", "frame_id", "pending_bytes"))
        if row["report_kind"] == "REPORT_ONLY":
            poll = self.polls[self.poll_frames[aid, frame]]
            require(poll.ended and not poll.reported and snapshot == poll.start and now == poll.end + self.up_prop,
                    "REPORT was not received in its real polling window")
            poll.reported = True
        if row["applied"] == "true":
            obj = self.allocations[aid]
            require(obj.report is None or snapshot >= obj.report[0], "Older REPORT overwrote MFU state")
            obj.report = snapshot, frame, pending
        if self.begin <= now < self.cutoff:
            self.add(aid, {"report_rx_count": 1})

    def grant_event(self, event: str, aid: int, row: dict, now: int) -> None:
        gid = row["grant_id"]
        frame, start, end, budget = (int(row[key]) for key in ("target_frame_id", "tx_start_ns", "tx_end_ns", "budget_bytes"))
        obj = self.allocations[aid]
        if event == "PON_DBA_BWMAP_TX":
            decision = self.frames[frame][aid]
            fixed = int(row.get("fixed_bytes", 0))
            require(gid not in self.grants and decision.grant_id is None and now == (frame - 1) * self.frame_ns
                    and now < self.stop and frame * self.frame_ns <= start < end <= (frame + 1) * self.frame_ns
                    and end - start == _serialized(budget, self.rate) and 0 <= fixed <= budget
                    and (budget == fixed or 0 < budget <= decision.known)
                    and (self.complete or not decision.outstanding), "Invalid DBA budget or next-frame reservation")
            grant = Grant(aid, frame, start, end, budget, fixed)
            decision.grant_id = gid
            self.grants[gid] = grant
            obj.inflight.add(gid)
            obj.report_grants.append(grant)
            self.reservations[frame].append((start, end))
            self.add(aid, {"data_reserved_ns": self.overlap(start, end)})
            return

        grant = self.grants[gid]
        require((grant.aid, grant.frame, grant.start, grant.end, grant.budget) == (aid, frame, start, end, budget),
                "DATA budget identity changed")
        if event == "PON_DBA_BWMAP_RX":
            require(not grant.received and now == (frame - 1) * self.frame_ns + self.down_prop and now < start,
                    "Missing or late DATA BWmap")
            grant.received = True
        elif event == "PON_DBA_FILL":
            self.fill(grant, obj, row, now)
        elif event in {"PON_DBA_DATA_START", "PON_DBA_DATA_END"}:
            self.data(event, grant, row, now)
        elif event == "PON_DBA_SLOT_END":
            require(grant.selected is not None and grant.tx_index == len(grant.selected) and not grant.slot_ended
                    and now == end and grant.used_bytes == grant.selected_bytes, "DATA budget ended before completing FILL")
            grant.slot_ended = True
        else:
            actual = int(row["actual_bytes"])
            require(grant.slot_ended and not grant.settled and now == end + self.up_prop
                    and actual == grant.used_bytes and int(row["unused_bytes"]) == budget - actual,
                    "DATA budget settlement violates time or byte conservation")
            grant.settled = True
            obj.inflight.remove(gid)

    def fill(self, grant: Grant, obj: Allocation, row: dict, now: int) -> None:
        selected = list(map(int, row["selected_packet_ids"].split(";"))) if row["selected_packet_ids"] != "-" else []
        require(grant.received and grant.selected is None and now == grant.start, "Unexpected repeated or ungranted FILL")
        size = duration = 0
        for pid in selected:
            require(bool(obj.fifo) and obj.fifo.popleft() == pid, "Grant loaded outside allocation FIFO order")
            packet = self.packets[pid]
            require(packet.load < 0 and packet.aid == grant.aid and 0 <= packet.enqueue <= now, "Invalid duplicate or future load")
            packet.load = now
            size += packet.size
            duration += _serialized(packet.size, self.rate)
            if self.begin <= now < self.cutoff:
                self.sample(packet.aid, "fifo", now - packet.enqueue)
        require(size == int(row["selected_bytes"]) <= grant.budget and duration == int(row["selected_duration_ns"])
                <= grant.end - grant.start and len(obj.fifo) == int(row["remaining_packets"]),
                "FILL exceeds byte or time allowance, or has wrong FIFO count")
        if selected:
            self.update_queue(grant.aid, now, -len(selected), -size)
        grant.selected, grant.selected_bytes = selected, size

    def data(self, event: str, grant: Grant, row: dict, now: int) -> None:
        pid = int(row["packet_id"])
        packet = self.packets[pid]
        require(grant.selected is not None and grant.tx_index < len(grant.selected) and grant.selected[grant.tx_index] == pid
                and packet.load == grant.start and grant.start <= now <= grant.end, "Invalid DATA packet or window")
        if event == "PON_DBA_DATA_START":
            expected = grant.start if not grant.tx_index else self.packets[grant.selected[grant.tx_index - 1]].end
            require(self.active is None and packet.start < 0 and now == expected, "Overlapping, late or repeated DATA start")
            self.active = ("DATA", pid)
            packet.start = now
        else:
            require(self.active == ("DATA", pid) and packet.end < 0 and now == packet.start + _serialized(packet.size, self.rate),
                    "Incorrect DATA serialization or END order")
            self.active = None
            packet.end = now
            grant.used_bytes += packet.size
            grant.tx_index += 1
            measured = self.begin <= packet.start and now <= self.cutoff
            self.add(grant.aid, {"data_busy_ns": self.overlap(packet.start, now), "observed_data_tx_packets": int(measured),
                                 "observed_data_tx_bytes": packet.size if measured else 0})

    def phases(self, aid: int, decision: Decision, actual: int, budget: int, in_window: bool) -> tuple[dict, list[tuple[int, int]]]:
        row = decision.phase_row
        obj, profile = self.allocations[aid], self.profiles[aid]
        require(row is not None and int(row["budget_bytes"]) == budget and int(row["known_demand_bytes"]) == decision.known,
                "GIANT phase trace disagrees with BWmap or MFU known demand")
        counter_keys = ("fixed", "max", "min", "min" if profile.kind == 4 else "be")
        before, after = [], []
        remaining = actual
        planned_so_far = 0
        values = {}
        orders = []
        for index, (phase, counter, interval, quota) in enumerate(zip(PHASES, counter_keys, profile.intervals, profile.amounts)):
            offered = int(row[f"{phase}_bytes"])
            order = int(row[f"{phase}_order"])
            if interval:
                old = int(row[f"si_{counter}_before"])
                new = int(row[f"si_{counter}_after"])
                previous = obj.last_phase_after[index] if obj.last_phase_after is not None else interval
            else:
                old = new = previous = 0
            before.append(old)
            after.append(new)
            due = old == 1
            carried = min(offered, remaining)
            remaining -= carried
            qualified = (quota if phase == "fixed" else min(quota, max(0, decision.known - planned_so_far))) if due else 0
            if phase == "assured":
                require(qualified == int(row["assured_wanted_bytes"]), "Assured demand disagrees with Java GIANT counters")
            planned_so_far += offered
            expected = interval if due and (index < 2 or offered) else max(0, old - 1) if old > 1 else old
            invalid = old != previous or new != expected or not 0 <= offered <= qualified or bool(offered) != (order > 0)
            if in_window:
                self.add(aid, {f"{phase}_configured_due_bytes": quota if due else 0, f"{phase}_qualified_bytes": qualified,
                               f"{phase}_authorized_bytes": offered, f"{phase}_attributed_actual_bytes": carried,
                               f"{phase}_authorization_shortfalls": int(offered < qualified),
                               f"{phase}_actual_shortfalls": int(carried < qualified),
                               f"{phase}_unknown_due_opportunities": int(phase != "fixed" and due and decision.known < 0),
                               "si_violations": int(invalid)})
            values.update({f"{phase}_si_before": old, f"{phase}_si_after": new, f"{phase}_quota_bytes": quota,
                           f"{phase}_qualified_bytes": qualified, f"{phase}_authorized_bytes": offered,
                           f"{phase}_attributed_actual_bytes": carried, f"{phase}_order": order,
                           f"{phase}_reason": row[f"{phase}_reason"]})
            if order > 0:
                orders.append((order, index))
        require(not remaining and planned_so_far == budget, "Merged GIANT phase budgets do not reconcile with DATA")
        obj.last_phase_after = tuple(after)
        return values, orders

    def measure_frame(self, frame: int) -> None:
        require(frame == self.last_measured_frame + 1, "Missing complete DBA target frame")
        self.last_measured_frame = frame
        replay = self.frames.pop(frame)
        require(replay.keys() == self.profiles.keys(), "DBA frame lacks decisions for one or more allocations")
        start, end = frame * self.frame_ns, (frame + 1) * self.frame_ns
        in_window = self.begin <= start and end <= self.cutoff
        slots = sorted(self.reservations.pop(frame, []))
        require(all(start <= first < last <= end for first, last in slots)
                and all(left[1] <= right[0] for left, right in zip(slots, slots[1:])), "Shared DATA or REPORT-only slots overlap")
        phase_orders = []
        frame_data_ns = frame_reserved_ns = 0
        for aid, decision in replay.items():
            profile, obj = self.profiles[aid], self.allocations[aid]
            grant = self.grants[decision.grant_id] if decision.grant_id else None
            require(grant is None or grant.settled, "Cannot measure frame before all DATA grants settle")
            budget, actual = (grant.budget, grant.used_bytes) if grant else (0, 0)
            details = {}
            if self.complete:
                details, orders = self.phases(aid, decision, actual, budget, in_window)
                phase_orders.extend(orders)
            if grant:
                require(grant.selected is not None, "Settled grant lacks SFU FILL")
                frame_reserved_ns += grant.end - grant.start
                frame_data_ns += sum(_serialized(self.packets[pid].size, self.rate) for pid in grant.selected)
            if in_window:
                self.measure_allocation_frame(aid, profile, obj, decision, frame, budget, actual)
            if self.keep_grant_trace:
                self.grant_rows.append(dict(scenario_id=self.case.name, seed=self.case.seed, policy=self.case.policy,
                                            target_frame_id=frame, in_observation=in_window, sfu_id=profile.sfu,
                                            allocation_id=aid, tcont_type=profile.kind, known_bytes=decision.known,
                                            report_age_ns=decision.age, report_frame_id=decision.report_frame,
                                            outstanding=decision.outstanding, grant_id=decision.grant_id,
                                            budget_bytes=budget, actual_bytes=actual, unused_bytes=budget - actual,
                                            slot_start_ns=grant.start if grant else None, **details))
        reserved_ns = sum(last - first for first, last in slots)
        control_ns = reserved_ns - frame_reserved_ns
        order_phases = [phase for _, phase in sorted(phase_orders)]
        inverse = int(order_phases != sorted(order_phases)
                      or [order for order, _ in sorted(phase_orders)] != list(range(1, len(phase_orders) + 1)))
        if in_window and self.complete:
            self.groups[("link", None, None, None)].values["phase_priority_order_violations"] += inverse
        self.frame_rows.append(dict(scenario_id=self.case.name, seed=self.case.seed, policy=self.case.policy,
                                    frame_id=frame, frame_start_ns=start, frame_end_ns=end, in_observation=in_window,
                                    data_reserved_ns=frame_reserved_ns, data_busy_ns=frame_data_ns, report_busy_ns=control_ns,
                                    unused_reservation_ns=frame_reserved_ns - frame_data_ns, reserved_busy_ns=reserved_ns,
                                    physical_busy_ns=frame_data_ns + control_ns, phase_priority_order_violations=inverse))

    def measure_allocation_frame(self, aid: int, profile: Profile, obj: Allocation, decision: Decision,
                                 frame: int, budget: int, actual: int) -> None:
        values = dict(report_samples=1, report_known_samples=int(decision.known >= 0),
                      report_unknown_samples=int(decision.known < 0), reserved_data_grants=int(budget > 0),
                      used_data_grants=int(actual > 0), empty_data_grants=int(budget > 0 and actual == 0),
                      budget_bytes=budget, used_budget_bytes=actual, unused_budget_bytes=budget - actual,
                      rr_outstanding_skip_frames=int(not self.complete and decision.outstanding and decision.known > 0 and budget == 0))
        if decision.known >= 0:
            self.sample(aid, "report_age", decision.age)
        if budget:
            if obj.last_grant_frame is not None:
                self.sample(aid, "grant_gap", (frame - obj.last_grant_frame) * self.frame_ns)
            obj.last_grant_frame = frame
        if actual:
            if obj.last_actual_frame is not None:
                self.sample(aid, "actual_service_gap", (frame - obj.last_actual_frame) * self.frame_ns)
            obj.last_actual_frame = frame
        obj.no_service_streak = obj.no_service_streak + 1 if decision.known > 0 and actual == 0 else 0
        for group in self.group_lookup[aid]:
            stats = group.values
            stats["max_known_backlog_no_service_frames"] = max(stats["max_known_backlog_no_service_frames"], obj.no_service_streak)
            if budget and group.last_grant_frame != frame:
                if group.last_grant_frame is not None:
                    group.samples["scope_grant_gap"].append((frame - group.last_grant_frame) * self.frame_ns)
                group.last_grant_frame = frame
            if actual and group.last_actual_frame != frame:
                if group.last_actual_frame is not None:
                    group.samples["scope_actual_service_gap"].append((frame - group.last_actual_frame) * self.frame_ns)
                group.last_actual_frame = frame
        # A common comparison schedule for RR and GIANT: configured fixed/assured
        # due frames counted from their initial counter, gated by visible demand for assurance.
        fixed_si, assured_si = profile.intervals[:2]
        fixed_due = profile.amounts[0] if fixed_si and (frame - self.first_frame + 1) % fixed_si == 0 else 0
        assured_due = profile.amounts[1] if assured_si and (frame - self.first_frame + 1) % assured_si == 0 else 0
        qualified = fixed_due + min(assured_due, max(0, decision.known - fixed_due))
        values.update(reference_due_bytes=fixed_due + assured_due, reference_qualified_bytes=qualified,
                      reference_grant_covered_bytes=min(qualified, budget), reference_actual_covered_bytes=min(qualified, actual),
                      reference_grant_violations=int(budget < qualified), reference_actual_violations=int(actual < qualified))
        self.add(aid, values)

    def accept(self, raw: str) -> None:
        event_at = raw.find("event=")
        if event_at < 0:
            if raw.startswith(("summary=PON_DBA_EXECUTION ", "summary=UL_GLOBAL ", "summary=PON_POLLING ", "summary=PON_UPLINK ")):
                row = fields(raw)
                name = row["summary"]
                require(name not in self.summary, "Duplicated Java run summary")
                self.summary[name] = row
            elif raw.startswith("summary=TRAFFIC_UL_STA "):
                row = fields(raw)
                self.generator_packets += int(row["generated_packets"])
                self.generator_bytes += int(row["generated_bytes"])
            elif " simulation_end_us=" in raw:
                require(self.simulation_end == -1, "Duplicated simulation end clock")
                self.simulation_end = _ns(fields(raw)["simulation_end_us"])
            return
        end = raw.find(" ", event_at)
        event = raw[event_at + 6:end]
        if event not in EVENTS:
            return
        if event == "ARRIVAL" and not ("node=STA " in raw and "direction=UL " in raw):
            return
        if event == "DELIVERED" and not ("node=MFU " in raw and "direction=UL " in raw):
            return
        if event in {"WIFI_COLLISION_DROP", "WIFI_RETRY_LIMIT_DROP"} and "direction=UL " not in raw:
            return
        row = fields(raw)
        now = _clock(row)
        require(now >= self.last_ns, "Retained Java events are out of chronological order")
        self.last_ns = now
        if event == "ARRIVAL":
            self.arrival(row, now)
        elif event in {"DELIVERED", "TCONT_ENQUEUE", "WIFI_COLLISION_DROP", "WIFI_RETRY_LIMIT_DROP"}:
            self.packet_event(event, row, now)
        else:
            aid = int(row["allocation_id"])
            require(self.profiles[aid].sfu == int(row["sfu_id"]), "DBA event assigned to wrong SFU")
            if event == "PON_DBA_OBJECT_STATE":
                if self.frames:
                    first = next(iter(self.frames))
                    if first <= int(row["target_frame_id"]) - 3:
                        self.measure_frame(first)
                self.decision(aid, row, now)
            elif event == "PON_GIANT_DECISION":
                decision = self.frames[int(row["target_frame_id"])][aid]
                require(self.complete and decision.phase_row is None, "Duplicate GIANT phase row or unexpected GIANT policy")
                decision.phase_row = row
            elif event == "TCONT_REPORT_RX":
                self.report(aid, row, now)
            elif event.startswith("PON_POLL_"):
                self.poll_event(event, aid, row, now)
            else:
                self.grant_event(event, aid, row, now)

    def reconcile(self) -> None:
        require(self.active is None and self.simulation_end >= self.last_ns
                and all(grant.settled for grant in self.grants.values())
                and all(poll.reported for poll in self.polls.values()), "Java output terminated before link events completed")
        for frame in sorted(self.frames):
            self.measure_frame(frame)
        require(self.last_measured_frame == (self.stop - 1) // self.frame_ns + 1, "DBA decisions did not reach configured stop")
        require(not self.reservations and all(not obj.inflight for obj in self.allocations.values()), "Open reservation at simulation end")
        require(all(packet.load < 0 or packet.delivered >= 0 for packet in self.packets.values()), "Loaded DATA packet was never delivered")
        link = self.groups[("link", None, None, None)].values
        sent = self.summary["UL_GLOBAL"]
        require((int(sent["delivered_packets"]), int(sent["delivered_bytes"]))
                == (link["final_delivered_packets"], link["final_delivered_bytes"]), "UL_GLOBAL delivery differs from replay")
        if self.case.config["traffic.mode"] == "poisson":
            require((self.generator_packets, self.generator_bytes) == (link["generated_packets"], link["generated_bytes"]),
                    "Java traffic generator summary differs from replay")
        state = self.summary["PON_DBA_EXECUTION"]
        budget = sum(grant.budget for grant in self.grants.values())
        actual = sum(grant.used_bytes for grant in self.grants.values())
        reserved = sum(grant.end - grant.start for grant in self.grants.values())
        real_data = sum(packet.end - packet.start for packet in self.packets.values() if packet.end >= 0)
        expected = len(self.grants), budget, actual, budget - actual, reserved, real_data, reserved - real_data, link["final_fifo_packets"]
        labels = ("grants", "budget_bytes", "actual_bytes", "unused_bytes", "reserved_ns", "actual_data_ns", "unused_ns", "pending_packets")
        require(tuple(int(state[key]) for key in labels) == expected and actual == link["final_delivered_bytes"],
                "Java DBA execution summary does not reconcile to shared slots or actual DATA")
        state = self.summary["PON_POLLING"]
        poll_ns = sum(poll.end - poll.start for poll in self.polls.values())
        require((len(self.polls), real_data, poll_ns, poll_ns + real_data)
                == tuple(int(state[key]) for key in ("polls", "data_busy_ns", "report_busy_ns", "total_busy_ns")),
                "Java polling summary disagrees with actual link time")

    def outcomes(self, keep_packet_trace: bool) -> list[dict]:
        rows = []
        for pid, packet in self.packets.items():
            aid, size = packet.aid, packet.size
            finals = "delivered" if packet.delivered >= 0 else "dropped" if packet.dropped >= 0 else "pending"
            if packet.delivered >= 0 and packet.delivered < self.cutoff:
                status = "delivered"
                self.count(aid, "delivered_at_cutoff", size)
            elif packet.dropped >= 0 and packet.dropped < self.cutoff:
                status = "dropped"
                self.count(aid, "dropped_at_cutoff", size)
            elif packet.generation >= self.cutoff:
                status = "not_generated"
            else:
                status = "pending"
                self.count(aid, "pending_at_cutoff", size)
            self.count(aid, "generated", size)
            self.count(aid, f"final_{finals}", size)
            if packet.enqueue >= 0 and packet.load < 0:
                self.count(aid, "final_fifo", size)
            if 0 <= packet.enqueue < self.cutoff and (packet.load < 0 or packet.load >= self.cutoff):
                self.count(aid, "fifo_pending_at_cutoff", size)
                for group in self.group_lookup[aid]:
                    group.values["fifo_max_censored_age_ns"] = max(group.values["fifo_max_censored_age_ns"], self.cutoff - packet.enqueue)
            if keep_packet_trace:
                profile = self.profiles[aid]
                timestamp = lambda value: value if value >= 0 else None
                rows.append(dict(scenario_id=self.case.name, policy=self.case.policy, seed=self.case.seed, packet_id=pid,
                                 sfu_id=profile.sfu, allocation_id=aid, tcont_type=profile.kind, access_category=packet.category,
                                 source_sta_id=packet.source, packet_bytes=size, generation_ns=packet.generation,
                                 pon_enqueue_ns=timestamp(packet.enqueue), pon_load_ns=timestamp(packet.load),
                                 pon_tx_start_ns=timestamp(packet.start), pon_tx_end_ns=timestamp(packet.end),
                                 mfu_delivery_ns=timestamp(packet.delivered), drop_ns=timestamp(packet.dropped),
                                 fifo_wait_ns=packet.load - packet.enqueue if packet.load >= 0 else None,
                                 e2e_delay_ns=packet.delivered - packet.generation if packet.delivered >= 0 else None,
                                 final_status=finals, status_at_cutoff=status))
        return rows

    def metrics(self) -> list[dict]:
        window = self.cutoff - self.begin
        case = self.case
        configured = defaultdict(Decimal)
        assurance = defaultdict(lambda: [0.0, 0.0])
        for aid, profile in self.profiles.items():
            rate_prefix = "traffic.sta."
            origin_ids = [sta for sta, owner in self.sta_owners.items() if owner == profile.sfu]
            # Used by the experiment to verify per-Alloc offered profiles even when traffic is zero.
            offered = sum(Decimal(str(case.config.get(f"{rate_prefix}{sta}.class.{ac}.rate.mbps", 0)))
                          for sta in origin_ids for ac in profile.categories)
            for key in profile.scopes:
                configured[key] += offered
                for index, (interval, amount) in enumerate(zip(profile.intervals[:2], profile.amounts[:2])):
                    if interval:
                        assurance[key][index] += amount * 8000 / (interval * self.frame_ns)
        rows = []
        for key, group in self.groups.items():
            scope, sfu, aid, kind = key
            group.queue_change(max(group.queue_last_ns, self.cutoff), self.begin, self.cutoff)
            values = group.values
            require(values["generated_packets"] == values["final_delivered_packets"] + values["final_dropped_packets"]
                    + values["final_pending_packets"] and values["generated_bytes"] == values["final_delivered_bytes"]
                    + values["final_dropped_bytes"] + values["final_pending_bytes"], "Traffic outcomes fail conservation")
            require(values["data_busy_ns"] <= values["data_reserved_ns"] and values["data_reserved_ns"] + values["report_busy_ns"]
                    <= window and group.queue_packets == values["final_fifo_packets"] and group.queue_bytes == values["final_fifo_bytes"],
                    "Measured optical queue or reserved time fails conservation")
            physical = values["data_busy_ns"] + values["report_busy_ns"]
            reserved = values["data_reserved_ns"] + values["report_busy_ns"]
            offered = values["generated_bytes"] * 8000 / self.traffic_end
            row = dict(values, scenario_id=case.name, seed=case.seed, policy=case.policy, load=getattr(case, "load", None),
                       scenario=getattr(case, "scenario", None), sfu_count=getattr(case, "sfu_count", None),
                       t4_mbps=getattr(case, "t4_mbps", None), variant=getattr(case, "variant", case.policy), scope=scope,
                       sfu_id=sfu, allocation_id=aid, tcont_type=kind, traffic_end_ns=self.traffic_end, decision_end_ns=self.stop,
                       observation_start_ns=self.begin, observation_end_ns=self.cutoff, simulation_end_ns=self.simulation_end,
                       configured_offered_mbps=float(configured[key]), offered_mbps=offered, offered_to_capacity_ratio=offered / self.rate,
                       throughput_mbps=values["delivered_bytes"] * 8000 / window,
                       first_half_throughput_mbps=values["first_half_delivered_bytes"] * 8000 / (self.midpoint - self.begin),
                       second_half_throughput_mbps=values["second_half_delivered_bytes"] * 8000 / (self.cutoff - self.midpoint),
                       actual_drop_fraction=_ratio(values["final_dropped_packets"], values["generated_packets"]),
                       overflow_status="unsupported", final_pending_fraction=_ratio(values["final_pending_packets"], values["generated_packets"]),
                       drain_delivered_packets=values["final_delivered_packets"] - values["delivered_at_cutoff_packets"],
                       avg_queue_packets=values["queue_area_packets_ns"] / window, avg_queue_bytes=values["queue_area_bytes_ns"] / window,
                       unused_reservation_ns=values["data_reserved_ns"] - values["data_busy_ns"], physical_busy_ns=physical,
                       reserved_busy_ns=reserved, resource_utilization=physical / window, reservation_utilization=reserved / window,
                       unused_budget_fraction=_ratio(values["unused_budget_bytes"], values["budget_bytes"]),
                       report_known_fraction=_ratio(values["report_known_samples"], values["report_samples"]),
                       configured_fixed_sla_mbps=assurance[key][0], configured_assured_sla_mbps=assurance[key][1],
                       configured_sla_mbps=sum(assurance[key]),
                       reference_grant_coverage=_ratio(values["reference_grant_covered_bytes"], values["reference_qualified_bytes"]),
                       reference_actual_coverage=_ratio(values["reference_actual_covered_bytes"], values["reference_qualified_bytes"]))
            row.update({f"{count}_{unit}": values[f"{count}_{unit}"] for count in COUNTS for unit in ("packets", "bytes")})
            for index, phase in enumerate(PHASES):
                profile = self.profiles[aid] if aid is not None else None
                row[f"configured_{phase}_si_frames"] = profile.intervals[index] if profile else None
                row[f"configured_{phase}_ab_bytes"] = profile.amounts[index] if profile else None
            for name in TIMES:
                row.update(_distribution(group.samples[name], name))
            # A zero measured count is different from an unavailable protocol statistic.
            optional = {"fifo_max_censored_age_ns", "overflow_dropped_packets"}
            optional |= {label for label in METRIC_COLUMNS if label.startswith(tuple(f"{phase}_" for phase in PHASES)) and not self.complete}
            optional |= {"si_violations", "phase_priority_order_violations"} if not self.complete else set()
            optional |= {label for label in METRIC_COLUMNS if label.startswith(tuple(f"{kind}_" for kind in TIMES))
                         and not label.endswith("_sample_count")}
            rows.append({label: row.get(label) if label in optional else row.get(label, 0) for label in METRIC_COLUMNS})
        return rows


def analyze_stream(lines: Iterable[str], case, *, keep_packet_trace: bool = False,
                   keep_grant_trace: bool = False) -> OverloadRun:
    """Validate and summarize one real Java execution without retaining stdout."""
    trace = Trace(case, keep_grant_trace)
    for number, line in enumerate(lines, 1):
        try:
            trace.accept(line)
        except (ValueError, KeyError) as err:
            raise ValueError(f"{case.name} Java log line {number}: {err}") from err
    packets = trace.outcomes(keep_packet_trace)
    trace.reconcile()
    protected = sha256()
    for (source, category), digest in sorted(trace.protected.items()):
        protected.update(pack("<q", source) + category.encode("ascii") + digest.digest())
    return OverloadRun(trace.metrics(), trace.word.hexdigest(), len(trace.packets), protected.hexdigest(),
                       packets, trace.frame_rows, trace.grant_rows)
