package trial;

public class OrderService {
    private final PriceCalculator calculator = new PriceCalculator();

    public int orderTotal(int unitPriceCents, int quantity) {
        return calculator.subtotal(unitPriceCents, quantity);
    }
}
