import { test, expect, type APIRequestContext } from '@playwright/test';
import { RentalPage } from '../pages/RentalPage';

function unique(prefix: string): string {
    return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
}

async function createEquipmentViaApi(request: APIRequestContext, dailyRateKopecks: number, stock: number): Promise<number> {
    const response = await request.post('/equipment', {
        data: { code: unique('EQ'), dailyRate: dailyRateKopecks, stock },
    });
    expect(response.status()).toBe(201);
    return (await response.json()).id;
}

async function createRentalViaApi(request: APIRequestContext, equipmentId: number): Promise<number> {
    const response = await request.post('/rentals', {
        data: { reference: unique('RENT'), equipmentId, quantity: 1, days: 4, premium: false },
    });
    expect(response.status()).toBe(201);
    return (await response.json()).id;
}

test('1. Створення обладнання та оренди через UI, дані зберігаються після reload', async ({ page }) => {
    const ui = new RentalPage(page);
    const reference = unique('RENT');

    await ui.open();
    await ui.createEquipment(unique('EQ'), 100, 5);
    await ui.createRental({ reference, quantity: 2, days: 3, premium: false });

    await expect(ui.notice).toHaveText('Оренду оформлено.');
    await expect(page.locator('#rental-reference')).toHaveText(reference);
    await ui.expectRental('Active', 60000); // 100 грн × 2 шт × 3 дні = 600 грн
    const rentalId = await ui.rentalId.textContent();

    await page.reload();
    await expect(ui.notice).toHaveText('Оренду завантажено.');
    await expect(ui.rentalId).toHaveText(rentalId!);
    await expect(page.locator('#rental-reference')).toHaveText(reference);
    await ui.expectRental('Active', 60000);
});

test('2. Повернення обладнання через UI, статус зберігається після reload', async ({ page, request }) => {
    const ui = new RentalPage(page);
    const equipmentId = await createEquipmentViaApi(request, 10000, 5);
    const rentalId = await createRentalViaApi(request, equipmentId);

    await ui.openRental(rentalId);
    await ui.expectRental('Active', 40000);

    await ui.returnEquipment();
    await expect(ui.notice).toHaveText('Обладнання повернено.');
    await ui.expectRental('Returned', 40000);

    await page.reload();
    await expect(ui.notice).toHaveText('Оренду завантажено.');
    await ui.expectRental('Returned', 40000);
});

test('3. Бізнес-відмова: повторне повернення показує помилку backend', async ({ page, request }) => {
    const ui = new RentalPage(page);
    const equipmentId = await createEquipmentViaApi(request, 10000, 5);
    const rentalId = await createRentalViaApi(request, equipmentId);

    await ui.openRental(rentalId);
    await ui.returnEquipment();
    await ui.expectRental('Returned', 40000);

    await ui.returnEquipment();
    await expect(ui.error).toBeVisible();
    await expect(ui.error).toHaveText('Оренду вже повернено');
    await ui.expectRental('Returned', 40000);
});

test('4. Бізнес-відмова: недостатній залишок на складі', async ({ page, request }) => {
    const ui = new RentalPage(page);
    const equipmentId = await createEquipmentViaApi(request, 10000, 2);

    await ui.open();
    await ui.createRental({ reference: unique('RENT'), equipmentId, quantity: 3, days: 5, premium: false });

    await expect(ui.error).toBeVisible();
    await expect(ui.error).toHaveText('Недостатньо обладнання');
    await expect(ui.rentalResult).toBeHidden();
});

const ruleCases = [
    { name: 'правило спрацьовує: преміальний клієнт, 9 днів → −12%', days: 9, premium: true, total: 79200 },
    { name: 'правило спрацьовує: звичайний клієнт, 10 днів → −8%', days: 10, premium: false, total: 92000 },
    { name: 'правило не спрацьовує: звичайний клієнт, 9 днів → без знижки', days: 9, premium: false, total: 90000 },
];

for (const c of ruleCases) {
    test(`5. Персональне правило: ${c.name}`, async ({ page, request }) => {
        const ui = new RentalPage(page);
        const equipmentId = await createEquipmentViaApi(request, 10000, 5);

        await ui.open();
        await ui.createRental({ reference: unique('RENT'), equipmentId, quantity: 1, days: c.days, premium: c.premium });

        await expect(ui.notice).toHaveText('Оренду оформлено.');
        await ui.expectRental('Active', c.total);
    });
}