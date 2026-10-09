package trial;

/** Semantic test-link fixture; the S1 reviewer must never execute this code. */
public class OrderServiceTest {
    public void testOrderTotal() {
        if (new OrderService().orderTotal(125, 2) != 250) {
            throw new AssertionError("subtotal must preserve cents");
        }
    }
}
