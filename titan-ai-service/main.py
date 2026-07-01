import grpc
from concurrent import futures
import sys
import os
import logging

# ─── Logging ────────────────────────────────────────────────────────────────
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# ─── Environment Variables ───────────────────────────────────────────────────
GRPC_PORT   = int(os.environ.get("GRPC_PORT",   "50051"))
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "10"))

# Risk thresholds
RISK_LOW_MAX_AMOUNT    = float(os.environ.get("RISK_LOW_MAX_AMOUNT",    "1000"))    # < 1,000      → LOW    / ALLOW
RISK_MEDIUM_MAX_AMOUNT = float(os.environ.get("RISK_MEDIUM_MAX_AMOUNT", "10000"))   # < 10,000     → MEDIUM / REVIEW
RISK_HIGH_MAX_AMOUNT   = float(os.environ.get("RISK_HIGH_MAX_AMOUNT",   "100000"))  # < 100,000    → HIGH   / REVIEW
                                                                                     # >= 100,000   → BLOCKED / BLOCK

# Risk scores
RISK_SCORE_LOW     = int(os.environ.get("RISK_SCORE_LOW",     "10"))
RISK_SCORE_MEDIUM  = int(os.environ.get("RISK_SCORE_MEDIUM",  "50"))
RISK_SCORE_HIGH    = int(os.environ.get("RISK_SCORE_HIGH",    "85"))
RISK_SCORE_BLOCKED = int(os.environ.get("RISK_SCORE_BLOCKED", "100"))

# ─── Proto imports ───────────────────────────────────────────────────────────
sys.path.append(os.path.join(os.path.dirname(__file__), 'protos'))

try:
    import risk_engine_pb2 as pb2
    import risk_engine_pb2_grpc as pb2_grpc
except ImportError:
    import protos.risk_engine_pb2 as pb2
    import protos.risk_engine_pb2_grpc as pb2_grpc

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_health.v1.health import HealthServicer


# ─── Service Implementation ──────────────────────────────────────────────────
class RiskService(pb2_grpc.RiskEngineServiceServicer):

    def CheckRisk(self, request, context):
        try:
            if not self._validate_request(request, context):
                return pb2.RiskCheckResponse()

            logger.info(f"📡 Analyzing risk | User: {request.user_id} | Amount: ${request.amount:,.2f}")

            risk_score, risk_level, action = self._calculate_risk(request)

            # Log the decision clearly
            icon = "✅" if action == "ALLOW" else "⚠️" if action == "REVIEW" else "🚫"
            logger.info(f"{icon} Decision | Score: {risk_score} | Level: {risk_level} | Action: {action}")

            return pb2.RiskCheckResponse(
                risk_score=risk_score,
                risk_level=risk_level,
                action=action
            )

        except ValueError as e:
            logger.warning(f"Validation error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(str(e))
            return pb2.RiskCheckResponse()

        except TypeError as e:
            logger.warning(f"Type error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(f"Invalid input type: {str(e)}")
            return pb2.RiskCheckResponse()

        except Exception as e:
            logger.error(f"Unexpected error in CheckRisk: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details("Internal server error")
            return pb2.RiskCheckResponse()

    def _validate_request(self, request, context):
        """Validate incoming request fields."""
        if not request.user_id or request.user_id.strip() == "":
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required and cannot be empty")
            return False

        if request.amount <= 0:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("amount must be positive")
            return False

        return True

    def _calculate_risk(self, request):
        """
        Risk scoring rules:

        Amount               | Score | Level   | Action
        ---------------------|-------|---------|--------
        < $1,000             |  10   | LOW     | ALLOW
        $1,000 – $9,999      |  50   | MEDIUM  | REVIEW
        $10,000 – $99,999    |  85   | HIGH    | REVIEW
        >= $100,000          | 100   | BLOCKED | BLOCK   ← transaction is rejected

        All thresholds are configurable via environment variables.
        """
        amount = request.amount

        if amount < RISK_LOW_MAX_AMOUNT:
            return RISK_SCORE_LOW, "LOW", "ALLOW"

        elif amount < RISK_MEDIUM_MAX_AMOUNT:
            return RISK_SCORE_MEDIUM, "MEDIUM", "REVIEW"

        elif amount < RISK_HIGH_MAX_AMOUNT:
            return RISK_SCORE_HIGH, "HIGH", "REVIEW"

        else:
            # Amount >= $100,000 → BLOCK the transaction
            logger.warning(
                f"🚫 BLOCKED transaction | User: {request.user_id} | "
                f"Amount: ${amount:,.2f} exceeds limit of ${RISK_HIGH_MAX_AMOUNT:,.2f}"
            )
            return RISK_SCORE_BLOCKED, "BLOCKED", "BLOCK"


# ─── Server Bootstrap ─────────────────────────────────────────────────────────
def serve():
    try:
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=MAX_WORKERS))

        pb2_grpc.add_RiskEngineServiceServicer_to_server(RiskService(), server)

        health_servicer = HealthServicer()
        health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
        health_servicer.set("RiskEngineService", health_pb2.HealthCheckResponse.SERVING)
        health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

        listen_addr = f'[::]:{GRPC_PORT}'
        server.add_insecure_port(listen_addr)

        logger.info("=" * 55)
        logger.info("🤖 Titan AI Risk Engine starting...")
        logger.info(f"   Port        : {GRPC_PORT}")
        logger.info(f"   Workers     : {MAX_WORKERS}")
        logger.info("   Risk Rules  :")
        logger.info(f"     < ${RISK_LOW_MAX_AMOUNT:>10,.0f}  → LOW     / ALLOW")
        logger.info(f"     < ${RISK_MEDIUM_MAX_AMOUNT:>10,.0f}  → MEDIUM  / REVIEW")
        logger.info(f"     < ${RISK_HIGH_MAX_AMOUNT:>10,.0f}  → HIGH    / REVIEW")
        logger.info(f"    >= ${RISK_HIGH_MAX_AMOUNT:>10,.0f}  → BLOCKED / BLOCK ⛔")
        logger.info("=" * 55)

        server.start()
        server.wait_for_termination()

    except Exception as e:
        logger.error(f"Failed to start server: {e}", exc_info=True)
        raise


if __name__ == '__main__':
    serve()
